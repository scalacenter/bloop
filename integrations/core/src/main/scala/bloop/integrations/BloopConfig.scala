package bloop.integrations

import java.io.{File, FileOutputStream}

case class BloopConfig(
    name: String,
    baseDirectory: File,
    dependencies: Seq[String],
    scalaOrganization: String,
    scalaName: String,
    scalaVersion: String,
    classpath: Seq[File],
    classesDir: File,
    scalacOptions: Seq[String],
    javacOptions: Seq[String],
    sourceDirectories: Seq[File],
    testFrameworks: Seq[Seq[String]],
    testOptions: Array[TestOption],
    fork: Boolean,
    javaHome: File,
    javaOptions: Seq[String],
    allScalaJars: Seq[File],
    tmp: File
) {
  private def seqToString[T](xs: Seq[T], sep: String = ","): String = xs.mkString(sep)
  private def toPaths(xs: Seq[File]): Seq[String] = xs.map(_.getAbsolutePath)
  def writeTo(target: File): Unit = {
    val properties = new java.util.Properties()
    properties.setProperty("name", name)
    properties.setProperty("baseDirectory", baseDirectory.getAbsolutePath)
    properties.setProperty("dependencies", seqToString(dependencies))
    properties.setProperty("scalaOrganization", scalaOrganization)
    properties.setProperty("scalaName", scalaName)
    properties.setProperty("scalaVersion", scalaVersion)
    properties.setProperty("classpath", seqToString(toPaths(classpath)))
    properties.setProperty("classesDir", classesDir.getAbsolutePath)
    properties.setProperty("scalacOptions", seqToString(scalacOptions, ";"))
    properties.setProperty("javacOptions", seqToString(javacOptions, ";"))
    properties.setProperty("sourceDirectories", seqToString(toPaths(sourceDirectories)))
    properties.setProperty("testFrameworks",
                           seqToString(testFrameworks.map(seqToString(_)), sep = ";"))
    properties.setProperty("testOptions", Serializable.serialize(testOptions))
    properties.setProperty("fork", fork.toString)
    properties.setProperty("javaHome", javaHome.getAbsolutePath)
    properties.setProperty("javaOptions", seqToString(javaOptions, ";"))
    properties.setProperty("allScalaJars", seqToString(toPaths(allScalaJars)))
    properties.setProperty("tmp", tmp.getAbsolutePath)

    val stream = new FileOutputStream(target)
    try properties.store(stream, null)
    finally stream.close()
  }
}
