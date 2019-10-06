package bloop.testing.ui

import bloop.ufansi

import scala.collection.mutable
import scala.util.{Failure, Success}

object Formatter extends Formatter

/**
 * Default implementation of [[Formatter]], also used by the default SBT test
 * framework. Allows some degree of customization of the formatted test results.
 */
trait Formatter {

  def formatColor: Boolean = true
  def formatTruncateHeight: Int = 15
  def formatWrapWidth: Int = Int.MaxValue >> 1 // halving here to avoid overflows later

  def formatValue(x: Any) = testValueColor("" + x)

  def toggledColor(t: ufansi.Attrs) = if (formatColor) t else ufansi.Attrs.Empty
  def testValueColor = toggledColor(ufansi.Color.Blue)
  def exceptionClassColor = toggledColor(ufansi.Underlined.On ++ ufansi.Color.LightRed)
  def exceptionMsgColor = toggledColor(ufansi.Color.LightRed)
  def exceptionPrefixColor = toggledColor(ufansi.Color.Red)
  def exceptionMethodColor = toggledColor(ufansi.Color.LightRed)
  def exceptionPunctuationColor = toggledColor(ufansi.Color.Red)
  def exceptionLineNumberColor = toggledColor(ufansi.Color.LightRed)

  def formatResultColor(success: Boolean) = toggledColor(
    if (success) ufansi.Color.Green
    else ufansi.Color.Red
  )

  def formatMillisColor = toggledColor(ufansi.Bold.Faint)

  def exceptionStackFrameHighlighter(s: StackTraceElement): Boolean = true

  def formatException(x: Throwable, leftIndent: String) = {
    val output = mutable.Buffer.empty[ufansi.Str]
    var current = x
    while (current != null) {
      val exCls = exceptionClassColor(current.getClass.getName)
      output.append(
        joinLineStr(
          lineWrapInput(
            current.getMessage match {
              case null => exCls
              case nonNull => ufansi.Str.join(exCls, ": ", exceptionMsgColor(nonNull))
            },
            leftIndent
          ),
          leftIndent
        )
      )

      val stack = current.getStackTrace

      StackMarker
        .filterCallStack(stack)
        .foreach { e =>
          // Scala.js for some reason likes putting in full-paths into the
          // filename slot, rather than just the last segment of the file-path
          // like Scala-JVM does. This results in that portion of the
          // stacktrace being terribly long, wrapping around and generally
          // being impossible to read. We thus manually drop the earlier
          // portion of the file path and keep only the last segment

          val filenameFrag: ufansi.Str = e.getFileName match {
            case null => exceptionLineNumberColor("Unknown")
            case fileName =>
              val shortenedFilename = fileName.lastIndexOf('/') match {
                case -1 => fileName
                case n => fileName.drop(n + 1)
              }
              ufansi.Str.join(
                exceptionLineNumberColor(shortenedFilename),
                ":",
                exceptionLineNumberColor(e.getLineNumber.toString)
              )
          }

          val frameIndent = leftIndent + "  "
          val wrapper =
            if (exceptionStackFrameHighlighter(e)) ufansi.Attrs.Empty
            else ufansi.Bold.Faint

          output.append(
            "\n",
            frameIndent,
            joinLineStr(
              lineWrapInput(
                wrapper(
                  ufansi.Str.join(
                    exceptionPrefixColor(e.getClassName + "."),
                    exceptionMethodColor(e.getMethodName),
                    exceptionPunctuationColor("("),
                    filenameFrag,
                    exceptionPunctuationColor(")")
                  )
                ),
                frameIndent
              ),
              frameIndent
            )
          )
        }
      current = current.getCause
      if (current != null) output.append("\n", leftIndent)
    }

    ufansi.Str.join(output.toSeq: _*)
  }

  def lineWrapInput(input: ufansi.Str, leftIndent: String): Seq[ufansi.Str] = {
    val output = mutable.Buffer.empty[ufansi.Str]
    val plainText = input.plainText
    var index = 0
    while (index < plainText.length) {
      val nextWholeLine = index + (formatWrapWidth - leftIndent.length)
      val (nextIndex, skipOne) = plainText.indexOf('\n', index + 1) match {
        case -1 =>
          if (nextWholeLine < plainText.length) (nextWholeLine, false)
          else (plainText.length, false)
        case n =>
          if (nextWholeLine < n) (nextWholeLine, false)
          else (n, true)
      }

      output.append(input.substring(index, nextIndex))
      if (skipOne) index = nextIndex + 1
      else index = nextIndex
    }
    output.toSeq
  }

  def joinLineStr(lines: Seq[ufansi.Str], leftIndent: String) = {
    ufansi.Str.join(lines.flatMap(Seq[ufansi.Str]("\n", leftIndent, _)).drop(2): _*)
  }

  def formatIcon(success: Boolean): ufansi.Str = {
    formatResultColor(success)(if (success) "+" else "X")
  }

  /*
  private[this] def prettyTruncate(r: Result, leftIndent: String): Str = {
    r.value match {
      case Success(()) => ""
      case Success(v) =>
        val wrapped = lineWrapInput(formatValue(v), leftIndent)
        val truncated =
          if (wrapped.length <= formatTruncateHeight) wrapped
          else wrapped.take(formatTruncateHeight) :+ testValueColor("...")

        joinLineStr(truncated, leftIndent)

      case Failure(e) => formatException(e, leftIndent)
    }
  }

  def wrapLabel(leftIndentCount: Int, r: Result, label: String): Str = {
    val leftIndent = "  " * leftIndentCount
    val lhs = Str.join(
      leftIndent,
      formatIcon(r.value.isInstanceOf[Success[_]]),
      " ",
      label,
      " ",
      formatMillisColor(r.milliDuration + "ms"),
      " "
    )

    val rhs = prettyTruncate(r, leftIndent + "  ")

    val sep =
      if (lhs.length + rhs.length <= formatWrapWidth && !rhs.plainText.contains('\n')) " "
      else "\n" + leftIndent + "  "

    lhs ++ sep ++ rhs
  }

  def formatSingle(path: Seq[String], r: Result): Option[Str] = Some {
    wrapLabel(0, r, path.mkString("."))
  }

  def formatSummary(topLevelName: String, results: HTree[String, Result]): Option[Str] =
    Some {

      val relabelled = results match {
        case HTree.Node(v, c @ _*) => HTree.Node(topLevelName, c: _*)
        case HTree.Leaf(r) => HTree.Leaf(r.copy(name = topLevelName))
      }
      val (rendered, totalTime) = rec(0, relabelled) {
        case (depth, Left((name, millis))) =>
          Str("  " * depth + "- " + name + " ") ++ formatMillisColor(millis + "ms")
        case (depth, Right(r)) => wrapLabel(depth, r, r.name)
      }

      rendered.mkString("\n")
    }

  private[this] def rec(depth: Int, r: HTree[String, Result])(
      f: (Int, Either[(String, Long), Result]) => Str
  ): (Seq[Str], Long) = {
    r match {
      case HTree.Leaf(l) => (Seq(f(depth, Right(l))), l.milliDuration)
      case HTree.Node(v, c @ _*) =>
        val (subStrs, subTimes) = c.map(rec(depth + 1, _)(f)).unzip
        val cumulativeTime = subTimes.sum
        val thisStr = f(depth, Left(v, cumulativeTime))
        (thisStr +: subStrs.flatten, cumulativeTime)
    }
	}
	*/
}
