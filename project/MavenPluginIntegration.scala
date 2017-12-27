package ch.epfl.scala.sbt.maven

import java.nio.charset.Charset

import org.apache.maven.model.Build
import org.apache.maven.plugin.descriptor.{MojoDescriptor, PluginDescriptor}
import org.apache.maven.plugin.logging.SystemStreamLog
import org.apache.maven.project.MavenProject
import org.apache.maven.tools.plugin.DefaultPluginToolsRequest
import org.codehaus.plexus.logging.Logger
import org.codehaus.plexus.logging.console.ConsoleLogger
import org.apache.maven.tools.plugin.extractor.annotations.scanner.{
  DefaultMojoAnnotationsScanner,
  MojoAnnotationsScannerRequest
}
import org.apache.maven.tools.plugin.generator.PluginDescriptorGenerator
import sbt.{AutoPlugin, Compile, Def, Keys, PluginTrigger, Plugins}
import org.codehaus.plexus.component.repository.ComponentDependency
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
      val project = new MavenProject(model)

      val baseDir = Keys.baseDirectory.value.toPath()
      project.setBasedir(baseDir.toFile())
      def shortenAndToString(f: File): String = baseDir.relativize(f.toPath()).toString()

      val build = new Build()
      build.setSourceDirectory(shortenAndToString(Keys.sourceDirectory.in(Compile).value))
      build.setTestSourceDirectory(shortenAndToString(Keys.sourceDirectory.in(Test).value))
      build.setOutputDirectory(shortenAndToString(Keys.classDirectory.in(Compile).value))
      build.setTestOutputDirectory(shortenAndToString(Keys.classDirectory.in(Test).value))
      project.setBuild(build)

      project
    }

    val artifactId: Def.Initialize[String] = Def.setting {
      val moduleName = Keys.moduleName.value
      s"${moduleName}_${Keys.scalaBinaryVersion.value}"
    }

    val resourceGenerators: Def.Initialize[Task[Seq[File]]] = Def.taskDyn {
      if (!MavenPluginKeys.mavenPlugin.value) Def.task(Nil)
      else {
        val task = Def.task {
          val descriptor = new PluginDescriptor()
          descriptor.setName(Keys.name.value)
          descriptor.setGroupId(Keys.organization.value)
          descriptor.setDescription(Keys.description.value)
          descriptor.setArtifactId(artifactId.value)
          descriptor.setVersion(Keys.version.value)
          descriptor.setGoalPrefix("bloop")
          descriptor.setIsolatedRealm(true)

          val logger = MavenPluginKeys.mavenLogger.value
          val project = MavenPluginKeys.mavenProject.value
          val extractor = new SbtJavaAnnotationsMojoDescriptorExtractor(project, logger)
          val classesDir = Keys.classDirectory.in(Compile).value
          val mojoDescriptors = extractor.getMojoDescriptors(classesDir, descriptor)
          mojoDescriptors.foreach(mojo => descriptor.addMojo(mojo))

          val deps = Keys.libraryDependencies.in(Runtime).value
          val resolution = Keys.update.in(Runtime).value
          descriptor.setDependencies(getDescriptorDependencies(resolution, deps))

          import sbt.io.syntax.fileToRichFile
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

    import sbt.librarymanagement.ModuleID
    def getDescriptorDependencies(
        resolution: UpdateReport,
        originalDeps: Seq[ModuleID]): java.util.List[ComponentDependency] = {
      val depsWithArtifacts = originalDeps.filterNot(_.explicitArtifacts.isEmpty)
      import scala.collection.JavaConverters._
      resolution.allModules.toList.map { module =>
        val dependency = new ComponentDependency()
        dependency.setGroupId(module.organization)
        dependency.setArtifactId(module.name)
        dependency.setVersion(module.revision)
        depsWithArtifacts.find((m: ModuleID) =>
          m.name == module.name && m.organization == module.organization && m.revision == module.revision) match {
          case Some(moduleWithArtifact) =>
            dependency.setType(moduleWithArtifact.explicitArtifacts.head.`type`)
          case None => ()
        }
        dependency
      }.asJava
    }

    import org.apache.maven.tools.plugin.extractor.annotations.JavaAnnotationsMojoDescriptorExtractor
    final class SbtJavaAnnotationsMojoDescriptorExtractor(mavenProject: MavenProject,
                                                          logger: Logger)
        extends JavaAnnotationsMojoDescriptorExtractor {
      private val scanner = {
        val scanner0 = new DefaultMojoAnnotationsScanner()
        scanner0.enableLogging(logger)
        scanner0
      }

      import scala.collection.JavaConverters._
      def getMojoDescriptors(classesDir: File,
                             pluginDescriptor: PluginDescriptor): Seq[MojoDescriptor] = {
        val scanRequest = new MojoAnnotationsScannerRequest()
        scanRequest.setProject(mavenProject)
        scanRequest.setClassesDirectories(List(classesDir).asJava)
        val annotatedClasses = scanner.scan(scanRequest)
        val javaClasses = super
          .discoverClasses(Charset.defaultCharset().displayName(), mavenProject)
        super.populateDataFromJavadoc(annotatedClasses, javaClasses)

        import org.apache.maven.tools.plugin.extractor.annotations.scanner.MojoAnnotatedClass
        val mapClazz = classOf[java.util.Map[_, _]]
        val descriptorClazz = classOf[PluginDescriptor]
        val hijackedMethod = this
          .getClass()
          .getSuperclass()
          .getDeclaredMethod("toMojoDescriptors", mapClazz, descriptorClazz)

        hijackedMethod.setAccessible(true)
        val descriptors =
          hijackedMethod.invoke(this, annotatedClasses, pluginDescriptor)
        descriptors.asInstanceOf[java.util.List[MojoDescriptor]].asScala.toList
      }
    }
  }
}
