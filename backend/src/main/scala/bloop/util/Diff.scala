package bloop.util

object Diff {
  def unifiedDiff(
      original: String,
      revised: String,
      obtained: String,
      expected: String
  ): String =
    compareContents(
      splitIntoLines(original),
      splitIntoLines(revised),
      obtained,
      expected
    )

  private def splitIntoLines(string: String): Seq[String] =
    string.trim.replace("\r\n", "\n").split("\n")

  private def compareContents(
      original: Seq[String],
      revised: Seq[String],
      obtained: String,
      expected: String
  ): String = {
    import scala.collection.JavaConverters._
    val diff = difflib.DiffUtils.diff(original.asJava, revised.asJava)
    if (diff.getDeltas.isEmpty) ""
    else {
      difflib.DiffUtils
        .generateUnifiedDiff(obtained, expected, original.asJava, diff, 1)
        .asScala
        .mkString("\n")
    }
  }
}
