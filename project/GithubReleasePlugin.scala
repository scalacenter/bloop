package build

import sbt._, Keys._, complete._, DefaultParsers._
import scala.util.Try
import GithubRelease._, keys._

case object SbtGithubReleasePlugin extends AutoPlugin {

  // This plugin will load automatically
  override def trigger = allRequirements
  override def requires = sbt.plugins.IvyPlugin

  val autoImport = GithubRelease.keys

  // Default settings
  override lazy val projectSettings = Seq[Setting[_]](
    ghreleaseNotes := { tagName =>
      val ver = tagName.stripPrefix("v")
      IO.read(baseDirectory.value / "notes" / s"${ver}.markdown")
    },
    ghreleaseGithubOrigin := githubOrigin(baseDirectory.value),
    ghreleaseRepoOrg := ghreleaseGithubOrigin.value
      .map(_.organization)
      .getOrElse(organization.value),
    ghreleaseRepoName := ghreleaseGithubOrigin.value.map(_.name).getOrElse(name.value),
    ghreleaseTitle := { tagName => s"${name.value} ${tagName}" },
    // According to the Semantic Versioning Specification (rule 9)
    // a version containing a hyphen is a pre-release version
    ghreleaseIsPrerelease := { _.matches(""".*-.*""") },
    ghreleaseMediaTypesMap := defs.ghreleaseMediaTypesMap,
    ghreleaseAssets := packagedArtifacts.value.values.toSeq,
    ghreleaseGetRepo := defs.ghreleaseGetRepo.value,
    ghreleaseGithubToken := {
      defs.githubTokenFromEnv(defs.defaultTokenEnvVar) orElse
        defs.githubTokenFromFile(defs.defaultTokenFile)
    },
    ghreleaseGetReleaseBuilder := Def.inputTaskDyn {
      defs.ghreleaseGetReleaseBuilder(tagNameArg.parsed)
    }.evaluated,
    githubRelease := Def.inputTaskDyn {
      defs.githubRelease(tagNameArg.parsed)
    }.evaluated
  )

  def tagNameArg: Def.Initialize[Parser[String]] = Def.setting {
    val gitOut: Try[String] = Try {
      sys.process.Process(Seq("git", "tag", "--list"), baseDirectory.value).!!
    }

    val suggestions: Try[Parser[String]] = gitOut.map { out =>
      oneOf(
        out.split('\n').map { tag => token(tag.trim) }
      )
    }

    val fallback = s"v${version.value}"

    (Space ~> suggestions.getOrElse(StringBasic)) ?? fallback
  }

  def githubOrigin(base: File): Option[GithubRelease.Origin] = {
    val gitOut: Try[String] = Try {
      sys.process.Process(Seq("git", "ls-remote", "--get-url", "origin"), base).!!
    }

    val repoExtractPattern = """/([^/]*)/([^/]*)""".r

    gitOut
      .map { out =>
        val path = new URI(out.trim).getPath
        path match {
          case repoExtractPattern(organization, name) =>
            Some(GithubRelease.Origin(organization, name))
          case _ => None
        }
      }
      .toOption
      .flatten
  }
}
