package sbt.internal.inc.bloop.internal

import java.io.File
import java.nio.file.Path
import java.{util => ju}

import sbt.internal.inc.Analysis
import sbt.internal.inc.Compilation
import sbt.internal.inc.Incremental
import sbt.internal.inc.PlainVirtualFileConverter
import sbt.internal.inc.SourceInfos
import sbt.internal.inc.UsedName
import sbt.internal.inc.UsedNames
import sbt.util.InterfaceUtil
import xsbt.api.APIUtil
import xsbt.api.HashAPI
import xsbt.api.NameHashing
import xsbti.Position
import xsbti.Problem
import xsbti.Severity
import xsbti.T2
import xsbti.UseScope
import xsbti.VirtualFile
import xsbti.VirtualFileRef
import xsbti.api.AnalyzedClass
import xsbti.api.ClassLike
import xsbti.api.Companions
import xsbti.api.DefinitionType
import xsbti.api.DependencyContext
import xsbti.api.ExternalDependency
import xsbti.api.InternalDependency
import xsbti.api.NameHash
import xsbti.api.SafeLazyProxy
import xsbti.compile.ClassFileManager
import xsbti.compile.IncOptions
import xsbti.compile.Output
import xsbti.compile.analysis.ReadStamps

/**
 * This class provides a thread-safe implementation of `xsbti.AnalysisCallback` which is required to compile with the
 * Triplequote Hydra compiler.
 *
 * In essence, the implementation is a merge of the `BloopAnalysisCallback` with the original sbt default `AnalysisCallback`
 * implementation (https://github.com/sbt/zinc/blob/develop/internal/zinc-core/src/main/scala/sbt/internal/inc/Compile.scala),
 * which is already thread-safe.
 *
 * IMPORTANT: All modifications made to BloopAnalysisCallback` must be replicated here.
 */
final class ConcurrentAnalysisCallback(
    internalBinaryToSourceClassName: String => Option[String],
    externalAPI: (Path, String) => Option[AnalyzedClass],
    stampReader: ReadStamps,
    output: Output,
    options: IncOptions,
    manager: ClassFileManager
) extends IBloopAnalysisCallback {

  private[this] val compilation: Compilation = Compilation(System.currentTimeMillis(), output)

  override def toString: String =
    (List("Class APIs", "Object APIs", "Binary deps", "Products", "Source deps") zip
      List(classApis, objectApis, binaryDeps, nonLocalClasses, intSrcDeps))
      .map { case (label, map) => label + "\n\t" + map.mkString("\n\t") }
      .mkString("\n")

  case class ApiInfo(
      publicHash: HashAPI.Hash,
      extraHash: HashAPI.Hash,
      classLike: ClassLike
  )

  import java.util.concurrent.{ConcurrentLinkedQueue, ConcurrentHashMap}
  import scala.collection.concurrent.TrieMap

  private type ConcurrentSet[A] = ConcurrentHashMap.KeySetView[A, java.lang.Boolean]

  private[this] val srcs = ConcurrentHashMap.newKeySet[Path]()
  private[this] val classApis = new TrieMap[String, ApiInfo]
  private[this] val objectApis = new TrieMap[String, ApiInfo]
  private[this] val classPublicNameHashes = new TrieMap[String, Array[NameHash]]
  private[this] val objectPublicNameHashes = new TrieMap[String, Array[NameHash]]
  private[this] val usedNames = new TrieMap[String, ConcurrentSet[UsedName]]
  private[this] val unreportedProblems = new TrieMap[Path, ConcurrentLinkedQueue[Problem]]
  private[this] val reportedProblems = new TrieMap[Path, ConcurrentLinkedQueue[Problem]]
  private[this] val mainClasses = new TrieMap[Path, ConcurrentLinkedQueue[String]]
  private[this] val binaryDeps = new TrieMap[Path, ConcurrentSet[Path]]

  // source file to set of generated (class file, binary class name); only non local classes are stored here
  private[this] val nonLocalClasses = new TrieMap[Path, ConcurrentSet[(Path, String)]]
  private[this] val localClasses = new TrieMap[Path, ConcurrentSet[Path]]
  // mapping between src class name and binary (flat) class name for classes generated from src file
  private[this] val classNames = new TrieMap[Path, ConcurrentSet[(String, String)]]
  // generated class file to its source class name
  private[this] val classToSource = new TrieMap[Path, String]
  // internal source dependencies
  private[this] val intSrcDeps = new TrieMap[String, ConcurrentSet[InternalDependency]]
  // external source dependencies
  private[this] val extSrcDeps = new TrieMap[String, ConcurrentSet[ExternalDependency]]
  private[this] val binaryClassName = new TrieMap[Path, String]
  // source files containing a macro def.
  private[this] val macroClasses = ConcurrentHashMap.newKeySet[String]()

  private[this] val converter = PlainVirtualFileConverter.converter

  private def add[A, B](map: TrieMap[A, ConcurrentSet[B]], a: A, b: B): Unit = {
    map.getOrElseUpdate(a, ConcurrentHashMap.newKeySet[B]()).add(b)
    ()
  }

  def startSource(source: VirtualFile): Unit = {
    val sourcePath = converter.toPath(source)
    if (options.strictMode()) {
      assert(
        !srcs.contains(source),
        s"The startSource can be called only once per source file: $source"
      )
    }
    srcs.add(sourcePath)
    ()
  }

  def startSource(source: File): Unit = {
    startSource(converter.toVirtualFile(source.toPath()))
  }

  def problem(
      category: String,
      pos: Position,
      msg: String,
      severity: Severity,
      reported: Boolean
  ): Unit = {
    for (source <- InterfaceUtil.jo2o(pos.sourceFile)) {
      val map = if (reported) reportedProblems else unreportedProblems
      map
        .getOrElseUpdate(source.toPath(), new ConcurrentLinkedQueue)
        .add(InterfaceUtil.problem(category, pos, msg, severity, None))
    }
  }

  def classDependency(
      onClassName: String,
      sourceClassName: String,
      context: DependencyContext
  ): Unit = {
    if (onClassName != sourceClassName)
      add(intSrcDeps, sourceClassName, InternalDependency.of(sourceClassName, onClassName, context))
  }

  private[this] def externalBinaryDependency(
      binary: Path,
      className: String,
      source: VirtualFileRef
  ): Unit = {
    binaryClassName.put(binary, className)
    add(binaryDeps, converter.toPath(source), binary)
  }

  private[this] def externalSourceDependency(
      sourceClassName: String,
      targetBinaryClassName: String,
      targetClass: AnalyzedClass,
      context: DependencyContext
  ): Unit = {
    val dependency =
      ExternalDependency.of(sourceClassName, targetBinaryClassName, targetClass, context)
    add(extSrcDeps, sourceClassName, dependency)
  }

  def binaryDependency(
      classFile: Path,
      onBinaryClassName: String,
      fromClassName: String,
      fromSourceFile: VirtualFileRef,
      context: DependencyContext
  ): Unit = {
    internalBinaryToSourceClassName(onBinaryClassName) match {
      case Some(dependsOn) => // dependsOn is a source class name
        // dependency is a product of a source not included in this compilation
        classDependency(dependsOn, fromClassName, context)
      case None =>
        classToSource.get(classFile) match {
          case Some(dependsOn) =>
            // dependency is a product of a source in this compilation step,
            //  but not in the same compiler run (as in javac v. scalac)
            classDependency(dependsOn, fromClassName, context)
          case None =>
            externalDependency(classFile, onBinaryClassName, fromClassName, fromSourceFile, context)
        }
    }
  }

  def binaryDependency(
      classFile: File,
      onBinaryClassName: String,
      fromClassName: String,
      fromSourceFile: File,
      context: DependencyContext
  ): Unit = {
    binaryDependency(
      classFile.toPath(),
      onBinaryClassName,
      fromClassName,
      converter.toVirtualFile(fromSourceFile.toPath()),
      context
    )
  }

  private[this] def externalDependency(
      classFile: Path,
      onBinaryName: String,
      sourceClassName: String,
      sourceFile: VirtualFileRef,
      context: DependencyContext
  ): Unit = {
    externalAPI(classFile, onBinaryName) match {
      case Some(api) =>
        // dependency is a product of a source in another project
        val targetBinaryClassName = onBinaryName
        externalSourceDependency(sourceClassName, targetBinaryClassName, api, context)
      case None =>
        // dependency is some other binary on the classpath
        externalBinaryDependency(classFile, onBinaryName, sourceFile)
    }
  }

  def generatedNonLocalClass(
      source: VirtualFileRef,
      classFile: Path,
      binaryClassName: String,
      srcClassName: String
  ): Unit = {
    val sourcePath = converter.toPath(source)
    // println(s"Generated non local class ${source}, ${classFile}, ${binaryClassName}, ${srcClassName}")
    add(nonLocalClasses, sourcePath, (classFile, binaryClassName))
    add(classNames, sourcePath, (srcClassName, binaryClassName))
    classToSource.put(classFile, srcClassName)
    ()
  }

  def generatedNonLocalClass(
      source: File,
      classFile: File,
      binaryClassName: String,
      srcClassName: String
  ): Unit = {
    generatedNonLocalClass(
      converter.toVirtualFile(source.toPath()),
      classFile.toPath(),
      binaryClassName,
      srcClassName
    )
  }

  override def generatedLocalClass(source: VirtualFileRef, classFile: Path): Unit = {
    add(localClasses, converter.toPath(source), classFile)
    ()
  }

  def generatedLocalClass(source: File, classFile: File): Unit = {
    generatedLocalClass(converter.toVirtualFile(source.toPath()), classFile.toPath())
  }

  override def api(sourceFile: VirtualFileRef, classApi: ClassLike): Unit = {
    import xsbt.api.{APIUtil, HashAPI}
    val className = classApi.name
    if (APIUtil.isScalaSourceName(sourceFile.name()) && APIUtil.hasMacro(classApi))
      macroClasses.add(className)
    val shouldMinimize = !Incremental.apiDebug(options)
    val savedClassApi = if (shouldMinimize) APIUtil.minimize(classApi) else classApi
    val apiHash: HashAPI.Hash = HashAPI(classApi)
    val nameHashes = (new xsbt.api.NameHashing(options.useOptimizedSealed())).nameHashes(classApi)
    classApi.definitionType match {
      case d @ (DefinitionType.ClassDef | DefinitionType.Trait) =>
        val extraApiHash = {
          if (d != DefinitionType.Trait) apiHash
          else HashAPI(_.hashAPI(classApi), includePrivateDefsInTrait = true)
        }

        classApis(className) = ApiInfo(apiHash, extraApiHash, savedClassApi)
        classPublicNameHashes(className) = nameHashes
      case DefinitionType.Module | DefinitionType.PackageModule =>
        objectApis(className) = ApiInfo(apiHash, apiHash, savedClassApi)
        objectPublicNameHashes(className) = nameHashes
    }
  }

  override def api(sourceFile: File, classApi: ClassLike): Unit = {
    api(converter.toVirtualFile(sourceFile.toPath()), classApi)
  }

  override def mainClass(sourceFile: VirtualFileRef, className: String): Unit = {
    mainClasses
      .getOrElseUpdate(converter.toPath(sourceFile), new ConcurrentLinkedQueue)
      .add(className)
    ()
  }

  def mainClass(sourceFile: File, className: String): Unit = {
    mainClass(converter.toVirtualFile(sourceFile.toPath()), className)
  }

  def usedName(className: String, name: String, useScopes: ju.EnumSet[UseScope]): Unit =
    add(usedNames, className, UsedName(name, useScopes))

  override def enabled(): Boolean = options.enabled

  override def get: Analysis = {
    addUsedNames(addCompilation(addProductsAndDeps(Analysis.empty)))
  }

  // According to docs this is used for build tools and it's not unused in Bloop
  override def isPickleJava(): Boolean = false
  override def getPickleJarPair(): ju.Optional[T2[Path, Path]] = ju.Optional.empty()

  def getOrNil[A, B](m: collection.Map[A, Seq[B]], a: A): Seq[B] = m.get(a).toList.flatten
  def addCompilation(base: Analysis): Analysis =
    base.copy(compilations = base.compilations.add(compilation))
  def addUsedNames(base: Analysis): Analysis = usedNames.foldLeft(base) {
    case (a, (className, names)) =>
      import scala.collection.JavaConverters._
      a.copy(relations =
        a.relations.addUsedNames(UsedNames.fromMultiMap(Map(className -> names.asScala)))
      )
  }

  private def companionsWithHash(className: String): (Companions, HashAPI.Hash, HashAPI.Hash) = {
    val emptyHash = -1
    val emptyClass =
      ApiInfo(emptyHash, emptyHash, APIUtil.emptyClassLike(className, DefinitionType.ClassDef))
    val emptyObject =
      ApiInfo(emptyHash, emptyHash, APIUtil.emptyClassLike(className, DefinitionType.Module))
    val ApiInfo(classApiHash, classHashExtra, classApi) = classApis.getOrElse(className, emptyClass)
    val ApiInfo(objectApiHash, objectHashExtra, objectApi) =
      objectApis.getOrElse(className, emptyObject)
    val companions = Companions.of(classApi, objectApi)
    val apiHash = (classApiHash, objectApiHash).hashCode
    val extraHash = (classHashExtra, objectHashExtra).hashCode
    (companions, apiHash, extraHash)
  }

  private def nameHashesForCompanions(className: String): Array[NameHash] = {
    val classNameHashes = classPublicNameHashes.get(className)
    val objectNameHashes = objectPublicNameHashes.get(className)
    (classNameHashes, objectNameHashes) match {
      case (Some(nm1), Some(nm2)) => NameHashing.merge(nm1, nm2)
      case (Some(nm), None) => nm
      case (None, Some(nm)) => nm
      case (None, None) => sys.error("Failed to find name hashes for " + className)
    }
  }

  private def analyzeClass(name: String): AnalyzedClass = {
    val hasMacro: Boolean = macroClasses.contains(name)
    val (companions, apiHash, extraHash) = companionsWithHash(name)
    val nameHashes = nameHashesForCompanions(name)
    val safeCompanions = SafeLazyProxy(companions)
    AnalyzedClass.of(
      compilation.getStartTime(),
      name,
      safeCompanions,
      apiHash,
      nameHashes,
      hasMacro,
      extraHash
    )
  }

  def addProductsAndDeps(base: Analysis): Analysis = {
    import scala.collection.JavaConverters._
    srcs.asScala.foldLeft(base) {
      case (a, src) =>
        val sourceV = converter.toVirtualFile(src)
        val stamp = stampReader.source(sourceV)
        val classesInSrc =
          classNames
            .getOrElse(src, ConcurrentHashMap.newKeySet[(String, String)]())
            .asScala
            .map(_._1)
        val analyzedApis = classesInSrc.map(analyzeClass)
        val info = SourceInfos.makeInfo(
          getOrNil(reportedProblems.mapValues { _.asScala.toSeq }, src),
          getOrNil(unreportedProblems.mapValues { _.asScala.toSeq }, src),
          getOrNil(mainClasses.mapValues { _.asScala.toSeq }, src)
        )
        val binaries = binaryDeps.getOrElse(src, ConcurrentHashMap.newKeySet[Path]).asScala
        val localProds = localClasses
          .getOrElse(src, ConcurrentHashMap.newKeySet[Path]())
          .asScala
          .map { classFile =>
            val classFileV = converter.toVirtualFile(classFile)
            val classFileStamp = stampReader.product(classFileV)
            Analysis.LocalProduct(classFileV, classFileStamp)
          }
        val binaryToSrcClassName =
          (classNames
            .getOrElse(src, ConcurrentHashMap.newKeySet[(String, String)]())
            .asScala
            .map {
              case (srcClassName, binaryClassName) => (binaryClassName, srcClassName)
            })
            .toMap
        val nonLocalProds = nonLocalClasses
          .getOrElse(src, ConcurrentHashMap.newKeySet[(Path, String)]())
          .asScala
          .map {
            case (classFile, binaryClassName) =>
              val classFileV = converter.toVirtualFile(classFile)
              val srcClassName = binaryToSrcClassName(binaryClassName)
              val classFileStamp = stampReader.product(classFileV)
              Analysis.NonLocalProduct(srcClassName, binaryClassName, classFileV, classFileStamp)
          }

        val internalDeps = classesInSrc.flatMap(cls =>
          intSrcDeps.getOrElse(cls, ConcurrentHashMap.newKeySet[InternalDependency]()).asScala
        )
        val externalDeps = classesInSrc.flatMap(cls =>
          extSrcDeps.getOrElse(cls, ConcurrentHashMap.newKeySet[ExternalDependency]()).asScala
        )
        val binDeps = binaries.map { d =>
          val virtual = converter.toVirtualFile(d)
          (virtual, binaryClassName(d), stampReader.library(virtual))
        }

        a.addSource(
          sourceV,
          analyzedApis,
          stamp,
          info,
          nonLocalProds,
          localProds,
          internalDeps,
          externalDeps,
          binDeps
        )
    }
  }

  override def apiPhaseCompleted(): Unit = {
    // See [[BloopAnalysisCallback.apiPhaseCompleted]]
    manager.generated(classToSource.keysIterator.map(converter.toVirtualFile).toArray)
  }
  override def dependencyPhaseCompleted(): Unit = ()
  override def classesInOutputJar(): java.util.Set[String] = ju.Collections.emptySet()

}
