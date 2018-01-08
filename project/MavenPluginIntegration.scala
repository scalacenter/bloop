package ch.epfl.scala.sbt.maven

import java.nio.charset.Charset

import org.apache.maven.artifact.handler.DefaultArtifactHandler
import org.apache.maven.artifact.{Artifact, DefaultArtifact}
import org.apache.maven.model.Build
import org.apache.maven.plugin.descriptor.{MojoDescriptor, Parameter, PluginDescriptor, PluginDescriptorBuilder, Requirement}
import org.apache.maven.plugin.logging.SystemStreamLog
import org.apache.maven.project.MavenProject
import org.apache.maven.tools.plugin.DefaultPluginToolsRequest
import org.codehaus.plexus.logging.Logger
import org.codehaus.plexus.logging.console.ConsoleLogger
import org.apache.maven.tools.plugin.extractor.annotations.scanner.{DefaultMojoAnnotationsScanner, MojoAnnotationsScannerRequest}
import org.apache.maven.tools.plugin.generator.PluginDescriptorGenerator
import sbt.{AutoPlugin, Compile, Def, Keys, PluginTrigger, Plugins}
import org.codehaus.plexus.component.repository.{ComponentDependency, ComponentRequirement}
import sbt.librarymanagement.UpdateReport

object MavenPluginIntegration extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = sbt.plugins.JvmPlugin
  val autoImport = MavenPluginKeys

  override def globalSettings: Seq[Def.Setting[_]] =
    MavenPluginImplementation.globalSettings
  override def buildSettings: Seq[Def.Setting[_]] =
    MavenPluginImplementation.buildSettings
  override def projectSettings: Seq[Def.Setting[_]] =
    MavenPluginImplementation.projectSettings
}

object MavenPluginKeys {
  import sbt.{settingKey, taskKey}
  val mavenPlugin = settingKey[Boolean](
    "If true, enables adding maven as a dependency and auto-generation of the plugin descriptor file.")
  val mavenLogger = settingKey[Logger]("The Plexus logger used for Maven APIs.")
  val mavenProject =
    taskKey[MavenProject]("Return the corresponding maven project.")
}

object MavenPluginImplementation {
  val globalSettings: Seq[Def.Setting[_]] = Nil
  val buildSettings: Seq[Def.Setting[_]] = Nil
  val projectSettings: Seq[Def.Setting[_]] = List(
    MavenPluginKeys.mavenPlugin := false,
    MavenPluginKeys.mavenLogger := MavenPluginDefaults.mavenLogger.value,
    MavenPluginKeys.mavenProject := MavenPluginDefaults.mavenProject.value,
    Keys.artifact in Keys.packageBin ~= { _.withType("maven-plugin") },
    Keys.resourceGenerators in Compile +=
      MavenPluginDefaults.resourceGenerators.taskValue
  )

  object MavenPluginDefaults {
    import sbt.{Task, File, Runtime, Test}
    val mavenProject: Def.Initialize[Task[MavenProject]] = Def.task {
      import org.apache.maven.model.io.DefaultModelReader
      import scala.collection.JavaConverters._
      val pomFile = Keys.makePom.value
      val pomFileReader = new DefaultModelReader()
      val model = pomFileReader.read(pomFile, Map[String, Any]().asJava)
      val build = new Build()
      val baseDir = Keys.baseDirectory.value.toPath()
      def shortenAndToString(f: File): String = baseDir.relativize(f.toPath()).toString()
      build.setSourceDirectory(shortenAndToString(Keys.sourceDirectory.in(Compile).value))
      build.setTestSourceDirectory(shortenAndToString(Keys.sourceDirectory.in(Test).value))
      build.setOutputDirectory(shortenAndToString(Keys.classDirectory.in(Compile).value))
      build.setTestOutputDirectory(shortenAndToString(Keys.classDirectory.in(Test).value))
      model.setBuild(build)

      new MavenProject(model) {
        def fs(f: File): String = f.getAbsolutePath()
        import scala.collection.JavaConverters._
        this.setCompileSourceRoots(Keys.sourceDirectories.in(Compile).value.map(fs).asJava)
        this.setTestCompileSourceRoots(Keys.sourceDirectories.in(Test).value.map(fs).asJava)
        override def getBasedir: File = baseDir.toFile()
      }
    }

    val artifactId: Def.Initialize[String] = Def.setting {
      val moduleName = Keys.moduleName.value
      s"${moduleName}_${Keys.scalaBinaryVersion.value}"
    }

    val resourceGenerators: Def.Initialize[Task[Seq[File]]] = Def.taskDyn {
      if (!MavenPluginKeys.mavenPlugin.value) Def.task(Nil)
      else {
        val task = Def.task {
          val BloopGoal = "bloop"
          val descriptor = new PluginDescriptor()
          descriptor.setName(Keys.name.value)
          descriptor.setGroupId(Keys.organization.value)
          descriptor.setDescription(Keys.description.value)
          descriptor.setArtifactId(artifactId.value)
          descriptor.setVersion(Keys.version.value)
          descriptor.setGoalPrefix(BloopGoal)
          descriptor.setIsolatedRealm(true)

          val logger = MavenPluginKeys.mavenLogger.value
          val project = MavenPluginKeys.mavenProject.value
          val classesDir = Keys.classDirectory.in(Compile).value

          val resolution = Keys.update.in(Runtime).value
          val artifacts = getArtifacts(resolution)
          descriptor.setDependencies(getDescriptorDependencies(artifacts))

          import sbt.io.syntax.fileToRichFile
          val annotationExtractor = new SbtJavaAnnotationsMojoDescriptorExtractor(project, logger)
          val annotationMojos =
            annotationExtractor.getMojoDescriptors(classesDir, descriptor, artifacts)
          annotationMojos.foreach(mojo => descriptor.addMojo(mojo))

          // This is the plugin dependent part of the Maven plugin setup
          val bloopMojoDescriptor = descriptor.getMojo(BloopGoal)
          val selector = PluginSelector("scala-maven-plugin", "cc")
          aggregateParametersFromDependentPlugins(artifacts, selector, bloopMojoDescriptor)

          val generator = new PluginDescriptorGenerator(new SystemStreamLog())
          val xmlDirectory = Keys.resourceManaged.in(Compile).value./("META-INF/maven")
          val request = new DefaultPluginToolsRequest(project, descriptor)
          generator.execute(xmlDirectory, request)
          Seq(xmlDirectory./("plugin.xml"))
        }
        task.dependsOn(Keys.compile in Compile)
      }
    }

    val mavenLogger: Def.Initialize[Logger] =
      Def.setting(new ConsoleLogger(Logger.LEVEL_INFO, s"sbt-build-${Keys.name.value}"))

    case class PluginSelector(name: String, goal: String)

    /**
     * Aggregates parameters from concrete dependent plugins.
     *
     * This function is custom and is not part of the conventional Maven plugin integration.
     * It exists because our `BloopMojo` extends a class that defines parameters. As we cannot
     * hijack them because they are almost all private, we need to tell Maven to trust us and
     * set the parameters in the super classes. If Maven does this correctly, then we can reuse
     * the functionality provided by the super class while avoiding to define the parameters
     * in our own mojo.
     *
     * @param artifacts The artifacts which our plugin depend on.
     * @param pluginSelector The selector that tells which parameters should be aggregated.
     * @param mojoDescriptor The descriptor of the mojo we need to inject the parameters in.
     * @return A mojo descriptor whose parameters are now correct.
     */
    def aggregateParametersFromDependentPlugins(
        artifacts: Set[Artifact],
        pluginSelector: PluginSelector,
        mojoDescriptor: MojoDescriptor
    ): MojoDescriptor = {
      import sbt.io.IO
      import sbt.io.syntax.fileToRichFile
      val dependentMavenPlugins = artifacts.filter(_.getType() == "maven-plugin")
      val dependencyPluginDescriptors = dependentMavenPlugins.map { (a: Artifact) =>
        IO.withTemporaryDirectory { tempDir =>
          val _ = IO.unzip(a.getFile(), tempDir)
          val pluginXml = tempDir./("META-INF/maven/plugin.xml")
          IO.reader(pluginXml)(reader => new PluginDescriptorBuilder().build(reader))
        }
      }

      val selectedMojos = dependencyPluginDescriptors
        .filter(_.getName() == pluginSelector.name)
        .flatMap(p => Option(p.getMojo(pluginSelector.goal)).toList)

      def fromRequirementToParameter(r: ComponentRequirement): Parameter = {
        val p = new Parameter()
        p.setRequired(true)
        p.setName(r.getFieldName)
        p.setRequirement(new Requirement(r.getRole(), r.getRoleHint))
        p
      }

      import scala.collection.JavaConverters._
      val existingParameters = mojoDescriptor.getParameters().asScala
      val pluginParameters = selectedMojos
        .flatMap(_.getParameters().asScala.toList.asInstanceOf[List[Parameter]])
        .filter(!existingParameters.contains(_))
      val pluginRequirementParameters = selectedMojos
        .flatMap(_.getRequirements().asScala.toList.asInstanceOf[List[ComponentRequirement]])
        .filter(p => !existingParameters.exists(p2 => p.getFieldName() == p2.getName()))
        .map(fromRequirementToParameter)
      val newPluginParameters = pluginParameters ++ pluginRequirementParameters
      mojoDescriptor.setParameters(newPluginParameters.toList.asJava)

      mojoDescriptor
    }

    /**
     * Convert Maven artifacts to component dependencies for the plugin descriptor.
     *
     * @param artifacts A list of maven artifacts.
     * @return A list of component dependencies.
     */
    def getDescriptorDependencies(artifacts: Set[Artifact]): java.util.List[ComponentDependency] = {
      import scala.collection.JavaConverters._
      artifacts.toList.map { artifact =>
        val dependency = new ComponentDependency()
        dependency.setGroupId(artifact.getGroupId())
        dependency.setArtifactId(artifact.getArtifactId())
        dependency.setVersion(artifact.getVersion())
        dependency.setType(artifact.getType())
        dependency
      }.asJava
    }

    private final val handler = new DefaultArtifactHandler("jar")

    /**
     * Convert sbt's update report to a set of artifacts that are dependencies that we
     * then will pass to the Maven APIs.
     *
     * @param resolution Sbt's update result.
     * @return A set of Maven artifacts.
     */
    def getArtifacts(resolution: UpdateReport): Set[Artifact] = {
      resolution.toVector.map {
        case (configRef, module, artifact, file) =>
          val classifier = artifact.classifier.getOrElse("")
          // FORMAT: OFF
          val mavenArtifact = new DefaultArtifact(module.organization, module.name, module.revision, configRef.name, artifact.`type`, classifier, handler)
          // FORMAT: ON
          mavenArtifact.setFile(file)
          artifact.url.foreach(url => mavenArtifact.setDownloadUrl(url.toString()))
          mavenArtifact.setResolved(true)
          mavenArtifact
      }.toSet
    }

    /**
     * Extracts the descriptors of Maven plugins that define themselves via Java annotations.
     *
     * @param mavenProject The project whose java annotations we're inspecting.
     * @param logger The logger that we should use to report.
     */
    final class SbtJavaAnnotationsMojoDescriptorExtractor(
        mavenProject: MavenProject,
        logger: Logger
    ) extends org.apache.maven.tools.plugin.extractor.annotations.JavaAnnotationsMojoDescriptorExtractor {

      private final val scanner = {
        val scanner0 = new DefaultMojoAnnotationsScanner()
        scanner0.enableLogging(logger)
        scanner0
      }

      import scala.collection.JavaConverters._

      /**
       * Get mojo descriptors using the clunky Maven APIs.
       *
       * This piece of code is really sensitive and it's handmade to avoid null pointer
       * exceptions caused by the lack of plexus dependency injection. It reuses the barebone
       * methods that provide the functionality and do not depend on plexus components. That's
       * why we cannot reuse most of `JavaAnnotationsMojoDescriptorExtractor`'s API.
       *
       * @param classesDir The classes directory where Maven looks for plugin annotations.
       * @param pluginDescriptor The work-in-progress plugin descriptor of the current plugin.
       * @param artifacts The dependencies (in artifacts) of the plugin.
       * @return A sequence of mojo descriptors to add to the wip plugin descriptor.
       */
      def getMojoDescriptors(classesDir: File,
                             pluginDescriptor: PluginDescriptor,
                             artifacts: Set[Artifact]): Seq[MojoDescriptor] = {
        val scanRequest = new MojoAnnotationsScannerRequest()
        scanRequest.setProject(mavenProject)
        scanRequest.setClassesDirectories(List(classesDir).asJava)
        scanRequest.setDependencies(artifacts.asJava)
        val annotatedClasses = scanner.scan(scanRequest)

        val encoding = Charset.defaultCharset().displayName()
        val sources = mavenProject.getCompileSourceRoots().asScala.toIterator
        val sourceDirs = sources.map(x => new File(x.toString)).toList
        val javaClasses = super.discoverClasses(encoding, sourceDirs.asJava, artifacts.asJava)
        super.populateDataFromJavadoc(annotatedClasses, javaClasses)

        val mapClazz = classOf[java.util.Map[_, _]]
        val descriptorClazz = classOf[PluginDescriptor]
        val hijackedMethod = this
          .getClass()
          .getSuperclass()
          .getDeclaredMethod("toMojoDescriptors", mapClazz, descriptorClazz)

        hijackedMethod.setAccessible(true)
        val descriptors = hijackedMethod.invoke(this, annotatedClasses, pluginDescriptor)
        descriptors.asInstanceOf[java.util.List[MojoDescriptor]].asScala.toList
      }
    }
  }
}
