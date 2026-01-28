//> using dep org.kohsuke:github-api:1.330
//> using toolkit 0.8.1

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.kohsuke.github.GitHubBuilder

val defaultToken =
  sys.env.get("GITHUB_TOKEN").getOrElse(throw new RuntimeException("GITHUB_TOKEN not set"))

@main
def main(
    firstTag: String,
    lastTag: String,
    githubToken: String = defaultToken
) = {

  val command = List(
    "git",
    "log",
    s"$firstTag..$lastTag",
    "--first-parent",
    "main",
    "--pretty=format:%H"
  )

  val outputBuilder = new StringBuilder()

  outputBuilder.append(s"# bloop `$lastTag`")
  outputBuilder.append("\n")
  outputBuilder.append("\n")
  outputBuilder.append(s"Bloop $lastTag is a bugfix release.")
  outputBuilder.append("\n")
  outputBuilder.append("\n")
  outputBuilder.append("## Installing Bloop")
  outputBuilder.append("\n")
  outputBuilder.append("\n")
  outputBuilder.append(
    "For more details about installing Bloop, please see [Bloop's Installation Guide](https://scalacenter.github.io/bloop/setup))"
  )
  outputBuilder.append("\n")
  outputBuilder.append("\n")
  outputBuilder.append("## Merged pull requests")
  outputBuilder.append("\n")
  outputBuilder.append("\n")
  outputBuilder.append("Here's a list of pull requests that were merged:")
  outputBuilder.append("\n")
  outputBuilder.append("\n")

  val output = os.proc(command).call().out.trim()

  val gh = new GitHubBuilder()
    .withOAuthToken(githubToken)
    .build()

  val changesList = new StringBuilder()
  val prsList = new StringBuilder()
  val foundPRs = mutable.Set.empty[Int]
  for {
    // group in order to optimize API
    searchSha <-
      output.split('\n').grouped(5).map(_.mkString("SHA ", " SHA ", ""))
    allMatching =
      gh.searchIssues().q(s"repo:scalacenter/bloop type:pr $searchSha").list()
    pr <- allMatching.toList().asScala.sortBy(_.getClosedAt()).reverse
    prNumber = pr.getNumber()
    if !foundPRs(prNumber)
  } {

    foundPRs += prNumber
    val login = pr.getUser().getLogin()
    val formattedPR =
      s"- ${pr.getTitle().capitalize} [#${prNumber}]\n"
    changesList.append(formattedPR)
    prsList.append(
      s"[#$prNumber]: https://github.com/scalacenter/bloop/pull/$prNumber\n"
    )
  }

  val contributorsCommand =
    List("git", "shortlog", "-sn", "--no-merges", s"$firstTag..$lastTag")
  val contributors = os
    .proc(contributorsCommand)
    .call()
    .out
    .trim()
    .replaceAll("\\d+", "")
    .split("\n")
    .map(_.trim)
    .mkString(" ", ", ", ".")

  outputBuilder.append(changesList.toString())
  outputBuilder.append("\n")
  outputBuilder.append("\n")
  outputBuilder.append(prsList.toString())
  outputBuilder.append("\n")
  outputBuilder.append("\n")
  outputBuilder.append("## Contributors")
  outputBuilder.append("\n")
  outputBuilder.append("\n")
  outputBuilder.append(
    s"""|According to `git shortlog -sn --no-merges ${firstTag}..${lastTag}`, the following people have contributed to
        |this `${lastTag}` release:""".stripMargin
  )
  outputBuilder.append(contributors.replaceAll("\\d+", ""))

  println(outputBuilder.toString())

}
