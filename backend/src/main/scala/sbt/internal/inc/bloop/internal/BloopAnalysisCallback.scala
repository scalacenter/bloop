package sbt.internal.inc.bloop.internal

import java.io.File
import java.{util => ju}

import xsbti.api.AnalyzedClass
import xsbti.compile.analysis.ReadStamps
import xsbti.compile.Output
import xsbti.compile.IncOptions
import xsbt.JarUtils
import xsbti.api.SafeLazyProxy
import xsbt.api.HashAPI
import xsbti.api.ClassLike
import xsbti.api.NameHash
import xsbti.Problem
import xsbti.api.InternalDependency
import xsbti.api.ExternalDependency
import xsbti.api.DependencyContext
import xsbti.Position
import xsbti.Severity
import xsbti.api.DefinitionType
import xsbti.UseScope
import xsbti.api.Companions
import xsbt.api.APIUtil
import xsbt.api.NameHashing
import xsbti.compile.ClassFileManager

import sbt.internal.inc.Incremental
import sbt.util.InterfaceUtil
import sbt.internal.inc.UsedName
import sbt.internal.inc.Analysis
import sbt.internal.inc.Compilation
import sbt.internal.inc.SourceInfos

import bloop.CompileMode
import bloop.ScalaSig
import xsbti.compile.Signature

final class BloopAnalysisCallback(
    compileMode: CompileMode,
    internalBinaryToSourceClassName: String => Option[String],
    internalSourceToClassNamesMap: File => Set[String],
    externalAPI: (File, String) => Option[AnalyzedClass],
    stampReader: ReadStamps,
    output: Output,
    options: IncOptions,
    manager: ClassFileManager
) extends xsbti.AnalysisCallback {
  private[this] val compilation: Compilation = Compilation(output)

  override def toString =
    (List("Class APIs", "Object APIs", "Binary deps", "Products", "Source deps") zip
      List(classApis, objectApis, binaryDeps, nonLocalClasses, intSrcDeps))
      .map { case (label, map) => label + "\n\t" + map.mkString("\n\t") }
      .mkString("\n")

  case class ApiInfo(
      publicHash: HashAPI.Hash,
      extraHash: HashAPI.Hash,
      classLike: ClassLike
  )

  import collection.mutable

  private[this] val srcs = mutable.HashSet[File]()
  private[this] val classApis = new mutable.HashMap[String, ApiInfo]
  private[this] val objectApis = new mutable.HashMap[String, ApiInfo]
  private[this] val classPublicNameHashes = new mutable.HashMap[String, Array[NameHash]]
  private[this] val objectPublicNameHashes = new mutable.HashMap[String, Array[NameHash]]
  private[this] val usedNames = new mutable.HashMap[String, mutable.HashSet[UsedName]]
  private[this] val unreportedProblems = new mutable.HashMap[File, mutable.ListBuffer[Problem]]
  private[this] val reportedProblems = new mutable.HashMap[File, mutable.ListBuffer[Problem]]
  private[this] val mainClasses = new mutable.HashMap[File, mutable.ListBuffer[String]]
  private[this] val binaryDeps = new mutable.HashMap[File, mutable.HashSet[File]]

  // source file to set of generated (class file, binary class name); only non local classes are stored here
  private[this] val nonLocalClasses = new mutable.HashMap[File, mutable.HashSet[(File, String)]]
  private[this] val localClasses = new mutable.HashMap[File, mutable.HashSet[File]]
  // mapping between src class name and binary (flat) class name for classes generated from src file
  private[this] val classNames = new mutable.HashMap[File, mutable.HashSet[(String, String)]]
  // generated class file to its source class name
  private[this] val classToSource = new mutable.HashMap[File, String]
  // internal source dependencies
  private[this] val intSrcDeps = new mutable.HashMap[String, mutable.HashSet[InternalDependency]]
  // external source dependencies
  private[this] val extSrcDeps = new mutable.HashMap[String, mutable.HashSet[ExternalDependency]]
  private[this] val binaryClassName = new mutable.HashMap[File, String]
  // source files containing a macro def.
  private[this] val macroClasses = mutable.HashSet[String]()

  private def add[A, B](map: mutable.HashMap[A, mutable.HashSet[B]], a: A, b: B): Unit = {
    map.getOrElseUpdate(a, new mutable.HashSet[B]()).+=(b)
    ()
  }

  def startSource(source: File): Unit = {
    if (options.strictMode()) {
      assert(
        !srcs.contains(source),
        s"The startSource can be called only once per source file: $source"
      )
    }
    srcs.add(source)
    ()
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
        .getOrElseUpdate(source, new mutable.ListBuffer())
        .+=(InterfaceUtil.problem(category, pos, msg, severity, None))
    }
  }

  def classDependency(onClassName: String, sourceClassName: String, context: DependencyContext) = {
    if (onClassName != sourceClassName)
      add(intSrcDeps, sourceClassName, InternalDependency.of(sourceClassName, onClassName, context))
  }

  private[this] def externalBinaryDependency(
      binary: File,
      className: String,
      source: File,
      context: DependencyContext
  ): Unit = {
    binaryClassName.put(binary, className)
    add(binaryDeps, source, binary)
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
      classFile: File,
      onBinaryClassName: String,
      fromClassName: String,
      fromSourceFile: File,
      context: DependencyContext
  ) = {
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

  private[this] def externalDependency(
      classFile: File,
      onBinaryName: String,
      sourceClassName: String,
      sourceFile: File,
      context: DependencyContext
  ): Unit = {
    externalAPI(classFile, onBinaryName) match {
      case Some(api) =>
        // dependency is a product of a source in another project
        val targetBinaryClassName = onBinaryName
        externalSourceDependency(sourceClassName, targetBinaryClassName, api, context)
      case None =>
        // dependency is some other binary on the classpath
        externalBinaryDependency(classFile, onBinaryName, sourceFile, context)
    }
  }

  def generatedNonLocalClass(
      source: File,
      classFile: File,
      binaryClassName: String,
      srcClassName: String
  ): Unit = {
    //println(s"Generated non local class ${source}, ${classFile}, ${binaryClassName}, ${srcClassName}")
    add(nonLocalClasses, source, (classFile, binaryClassName))
    add(classNames, source, (srcClassName, binaryClassName))
    classToSource.put(classFile, srcClassName)
    ()
  }

  def generatedLocalClass(source: File, classFile: File): Unit = {
    //println(s"Generated local class ${source}, ${classFile}")
    add(localClasses, source, classFile)
    ()
  }

  def api(sourceFile: File, classApi: ClassLike): Unit = {
    import xsbt.api.{APIUtil, HashAPI}
    val className = classApi.name
    if (APIUtil.isScalaSourceName(sourceFile.getName) && APIUtil.hasMacro(classApi))
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

  def mainClass(sourceFile: File, className: String): Unit = {
    mainClasses.getOrElseUpdate(sourceFile, new mutable.ListBuffer).+=(className)
    ()
  }

  def usedName(className: String, name: String, useScopes: ju.EnumSet[UseScope]) =
    add(usedNames, className, UsedName(name, useScopes))

  override def enabled(): Boolean = options.enabled

  def get: Analysis = {
    addUsedNames(addCompilation(addProductsAndDeps(Analysis.empty)))
  }

  def getOrNil[A, B](m: collection.Map[A, Seq[B]], a: A): Seq[B] = m.get(a).toList.flatten
  def addCompilation(base: Analysis): Analysis =
    base.copy(compilations = base.compilations.add(compilation))
  def addUsedNames(base: Analysis): Analysis = (base /: usedNames) {
    case (a, (className, names)) =>
      (a /: names) {
        case (a, name) => a.copy(relations = a.relations.addUsedName(className, name))
      }
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
    (base /: srcs) {
      case (a, src) =>
        val stamp = stampReader.source(src)
        val classesInSrc =
          classNames.getOrElse(src, new mutable.HashSet[(String, String)]()).map(_._1)
        val analyzedApis = classesInSrc.map(analyzeClass)
        val info = SourceInfos.makeInfo(
          getOrNil(reportedProblems, src),
          getOrNil(unreportedProblems, src),
          getOrNil(mainClasses, src)
        )
        val binaries = binaryDeps.getOrElse(src, Nil: Iterable[File])
        val localProds = localClasses
          .getOrElse(src, new mutable.HashSet[File]())
          .map { classFile =>
            val classFileStamp = stampReader.product(classFile)
            Analysis.LocalProduct(classFile, classFileStamp)
          }
        val binaryToSrcClassName =
          (classNames
            .getOrElse(src, new mutable.HashSet[(String, String)]())
            .map {
              case (srcClassName, binaryClassName) => (binaryClassName, srcClassName)
            })
            .toMap
        val nonLocalProds = nonLocalClasses
          .getOrElse(src, Nil: Iterable[(File, String)])
          .map {
            case (classFile, binaryClassName) =>
              val srcClassName = binaryToSrcClassName(binaryClassName)
              val classFileStamp = stampReader.product(classFile)
              Analysis.NonLocalProduct(srcClassName, binaryClassName, classFile, classFileStamp)
          }

        val internalDeps = classesInSrc.flatMap(
          cls => intSrcDeps.getOrElse(cls, new mutable.HashSet[InternalDependency]())
        )
        val externalDeps = classesInSrc.flatMap(
          cls => extSrcDeps.getOrElse(cls, new mutable.HashSet[ExternalDependency]())
        )
        val binDeps = binaries.map(d => (d, binaryClassName(d), stampReader binary d))

        a.addSource(
          src,
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

  override def apiPhaseCompleted(): Unit = ()
  override def dependencyPhaseCompleted(): Unit = ()
  override def classesInOutputJar(): java.util.Set[String] = ju.Collections.emptySet()

  override def definedMacro(symbolName: String): Unit = {
    compileMode.oracle.registerDefinedMacro(symbolName)
  }

  override def invokedMacro(invokedMacroSymbol: String): Unit = {
    compileMode.oracle.blockUntilMacroClasspathIsReady(invokedMacroSymbol)
  }

  override def isPipeliningEnabled(): Boolean = compileMode.oracle.isPipeliningEnabled
  override def downstreamSignatures(): Array[Signature] =
    compileMode.oracle.collectDownstreamSignatures()
  override def definedSignatures(signatures: Array[Signature]): Unit = {
    compileMode.oracle.startDownstreamCompilations(signatures)
  }

  override def invalidatedClassFiles(): Array[File] = {
    manager.invalidatedClassFiles()
  }
}
