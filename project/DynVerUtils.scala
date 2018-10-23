package sbtdynver
import java.io.File

object DynVerUtils {
  import scala.util.Try
  private val errLogger = scala.sys.process.ProcessLogger(stdOut => (), stdErr => println(stdErr))
  private def execAndHandleEmptyOutput(cmd: String, wd: Option[File]): Try[String] = {
    Try(scala.sys.process.Process(cmd, wd) !! errLogger)
      .filter(_.trim.nonEmpty)
  }

  def getGitPreviousStableTag(wd: Option[File]): Option[GitDescribeOutput] = {
    (for {
      // Find the parent of the current commit. The "^2" instructs it to pick the second parent
      parent <- execAndHandleEmptyOutput(s"git --no-pager log --pretty=%H -n 1 HEAD^1", wd)
      _ = println(s"Parent is ${parent}")
      // Find the closest tag of the parent commit
      tag <- execAndHandleEmptyOutput(s"git describe --tags --abbrev=0 --always $parent", wd)
    } yield GitDescribeOutput.parse(tag)).toOption
  }
}
