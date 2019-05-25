package bloop

import bloop.scalasig.ScalaSigWriter
import bloop.io.AbsolutePath
import bloop.scalasig.PickleMarker

import monix.eval.Task
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import bloop.logging.Logger
import bloop.logging.DebugFilter

final case class ScalaSig(path: String, bytes: Array[Byte])
object ScalaSig {
  def write(picklesDir: AbsolutePath, sig: ScalaSig, logger: Logger): Task[Unit] = {
    Task {
      val targetPicklePath = picklesDir.resolve(sig.path)
      val rawClassFileName = targetPicklePath.underlying.getFileName().toString
      val dummyClassPath = targetPicklePath.getParent.resolve(s"${rawClassFileName}.class")
      val bytes = toBinary(rawClassFileName, sig)
      logger.debug(s"Writing pickle to $dummyClassPath")(DebugFilter.Compilation)
      Files.write(dummyClassPath.underlying, bytes)
      ()
    }
  }

  def toBinary(className: String, sig: ScalaSig): Array[Byte] = {
    import org.objectweb.asm._
    import org.objectweb.asm.Opcodes._
    import org.objectweb.asm.tree._
    val classWriter = new ClassWriter(0)
    // TODO: Check that class name is correctly encoded if it has invalid symbols
    classWriter.visit(
      V1_8,
      ACC_PUBLIC + ACC_SUPER,
      className,
      null,
      "java/lang/Object",
      null
    )
    /*if (classfile.source.nonEmpty) {
      classWriter.visitSource(classfile.source, null)
    }*/
    val packedScalasig = ScalaSigWriter.packScalasig(sig.bytes)
    packedScalasig match {
      case Array(packedScalasig) =>
        val desc = "Lscala/reflect/ScalaSignature;"
        val av = classWriter.visitAnnotation(desc, true)
        av.visit("bytes", packedScalasig)
        av.visitEnd()
      case packedScalasigChunks =>
        val desc = "Lscala/reflect/ScalaLongSignature;"
        val av = classWriter.visitAnnotation(desc, true)
        val aav = av.visitArray("bytes")
        packedScalasigChunks.foreach(aav.visit("bytes", _))
        aav.visitEnd()
        av.visitEnd()
    }
    classWriter.visitAttribute(new PickleMarker)
    classWriter.visitEnd()
    classWriter.toByteArray
  }
}
