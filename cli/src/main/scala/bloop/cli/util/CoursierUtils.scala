package bloop.cli.util

object CoursierUtils {
  implicit class ModuleOps(private val mod: dependency.Module) extends AnyVal {
    def toCs: coursier.Module =
      coursier.Module(
        coursier.Organization(mod.organization),
        coursier.ModuleName(mod.name),
        mod.attributes
      )
  }
  implicit class ScalaModuleOps(private val mod: dependency.AnyModule) extends AnyVal {
    def toCs(params: dependency.ScalaParameters): coursier.Module =
      mod.applyParams(params).toCs
    def toCs(
        paramsOpt: Option[dependency.ScalaParameters]
    ): Either[Artifacts.NoScalaVersionProvidedError, coursier.Module] =
      paramsOpt match {
        case Some(params) => Right(toCs(params))
        case None =>
          val isJavaMod = mod.nameAttributes == dependency.NoAttributes
          if (isJavaMod)
            Right(mod.asInstanceOf[dependency.Module].toCs)
          else
            Left(new Artifacts.NoScalaVersionProvidedError(Left(mod)))
      }
  }

  implicit class AnyDependencyOps(private val dep: dependency.AnyDependency) extends AnyVal {
    def getUserParam(key: String) = {
      dep.userParams.collectFirst {
        case (`key`, value) => value
      }.flatten
    }
  }

  implicit class DependencyOps(private val dep: dependency.Dependency) extends AnyVal {
    def toCs: coursier.Dependency = {
      val mod = dep.module.toCs
      var dep0 = coursier.Dependency(mod, dep.version)
      if (dep.exclude.nonEmpty)
        dep0 = dep0.withExclusions {
          dep.exclude.toSet[dependency.Module].map { mod =>
            (coursier.Organization(mod.organization), coursier.ModuleName(mod.name))
          }
        }

      for (cl <- dep.getUserParam("classifier"))
        dep0 = dep0.withPublication(dep0.publication.withClassifier(coursier.core.Classifier(cl)))
      for (tpe <- dep.getUserParam("type"))
        dep0 = dep0.withPublication(dep0.publication.withType(coursier.core.Type(tpe)))
      for (ext <- dep.getUserParam("ext"))
        dep0 = dep0.withPublication(dep0.publication.withExt(coursier.core.Extension(ext)))
      for (_ <- dep.getUserParam("intransitive"))
        dep0 = dep0.withTransitive(false)
      dep0
    }
  }
  implicit class ScalaDependencyOps(private val dep: dependency.AnyDependency) extends AnyVal {
    def toCs(params: dependency.ScalaParameters): coursier.Dependency =
      dep.applyParams(params).toCs
    def toCs(
        paramsOpt: Option[dependency.ScalaParameters]
    ): Either[Artifacts.NoScalaVersionProvidedError, coursier.Dependency] =
      paramsOpt match {
        case Some(params) => Right(toCs(params))
        case None =>
          val isJavaDep =
            dep.module.nameAttributes == dependency.NoAttributes && dep.exclude.forall(
              _.nameAttributes == dependency.NoAttributes
            )
          if (isJavaDep)
            Right(dep.asInstanceOf[dependency.Dependency].toCs)
          else
            Left(new Artifacts.NoScalaVersionProvidedError(Right(dep)))
      }
  }
}
