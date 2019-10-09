package bloop.dap

import java.util
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.net.URI

import com.microsoft.java.debug.core.IEvaluatableBreakpoint
import com.microsoft.java.debug.core.adapter.{
  IProviderContext,
  ProviderContext,
  ICompletionsProvider,
  IEvaluationProvider,
  ISourceLookUpProvider,
  IHotCodeReplaceProvider,
  IVirtualMachineManagerProvider,
  HotCodeReplaceEvent
}
import com.microsoft.java.debug.core.protocol.Types
import io.reactivex.Observable
import com.sun.jdi.{
  ObjectReference,
  StackFrame,
  ThreadReference,
  Value,
  Bootstrap,
  VirtualMachineManager
}
import org.objectweb.asm.ClassReader
import java.nio.file.Path
import java.nio.file.Files
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Label
import scala.collection.mutable
import monix.execution.misc.NonFatal

object DebugExtensions {
  def newContext(runner: DebuggeeRunner): IProviderContext = {
    val context = new ProviderContext
    context.registerProvider(classOf[IHotCodeReplaceProvider], HotCodeReplaceProvider)
    context.registerProvider(classOf[IVirtualMachineManagerProvider], VirtualMachineManagerProvider)
    context.registerProvider(classOf[ISourceLookUpProvider], new SourceLookUpProvider(runner))
    context.registerProvider(classOf[IEvaluationProvider], EvaluationProvider)
    context.registerProvider(classOf[ICompletionsProvider], CompletionsProvider)
    context
  }

  object CompletionsProvider extends ICompletionsProvider {
    override def codeComplete(
        frame: StackFrame,
        snippet: String,
        line: Int,
        column: Int
    ): util.List[Types.CompletionItem] = Collections.emptyList()
  }

  object EvaluationProvider extends IEvaluationProvider {
    override def isInEvaluation(thread: ThreadReference): Boolean = false

    override def evaluate(
        expression: String,
        thread: ThreadReference,
        depth: Int
    ): CompletableFuture[Value] = ???

    override def evaluate(
        expression: String,
        thisContext: ObjectReference,
        thread: ThreadReference
    ): CompletableFuture[Value] = ???

    override def evaluateForBreakpoint(
        breakpoint: IEvaluatableBreakpoint,
        thread: ThreadReference
    ): CompletableFuture[Value] = ???

    override def invokeMethod(
        thisContext: ObjectReference,
        methodName: String,
        methodSignature: String,
        args: Array[Value],
        thread: ThreadReference,
        invokeSuper: Boolean
    ): CompletableFuture[Value] = ???

    override def clearState(thread: ThreadReference): Unit = {}
  }

  object HotCodeReplaceProvider extends IHotCodeReplaceProvider {
    override def onClassRedefined(consumer: Consumer[util.List[String]]): Unit = ()
    override def redefineClasses(): CompletableFuture[util.List[String]] =
      CompletableFuture.completedFuture(util.Collections.emptyList())
    override def getEventHub: Observable[HotCodeReplaceEvent] = Observable.empty()
  }

  final class SourceLookUpProvider(runner: DebuggeeRunner) extends ISourceLookUpProvider {
    override def supportsRealtimeBreakpointVerification(): Boolean = true
    override def getSourceFileURI(fqn: String, path: String): String = path
    override def getSourceContents(uri: String): String = ""

    import java.io.File

    override def getFullyQualifiedName(
        uriRepr: String,
        lines: Array[Int],
        columns: Array[Int]
    ): Array[String] = {
      val uri = URI.create(uriRepr)
      if (uri.getScheme() == "dap-fqcn") {
        val resolvedName = uri.getSchemeSpecificPart()
        Array(resolvedName)
      } else {
        val originSource = java.nio.file.Paths.get(uri)
        val classFiles = runner.classFilesMappedTo(originSource, lines, columns)
        lines.map(line => definingName(line, classFiles))
      }
    }

    private def collectLineNumbers(
        reader: ClassReader,
        lines: mutable.HashMap[Int, String]
    ): Unit = {
      val className = reader.getClassName()
      val visitor = new ClassVisitor(Opcodes.ASM6) {
        override def visitMethod(
            access: Int,
            name: String,
            desc: String,
            signature: String,
            exceptions: Array[String]
        ): MethodVisitor = {
          new MethodVisitor(Opcodes.ASM6) {
            override def visitLineNumber(line: Int, start: Label): Unit = {
              lines.+=(line -> className)
            }
          }
        }
      }

      reader.accept(visitor, 0)
    }

    /**
     * Parses all class files defined in a compilation unit and returns the
     * candidate that defines an instruction at line [[line]].
     */
    private def definingName(line: Int, candidates: Seq[Path]): String = {
      var firstName: String = ""
      val lines = new mutable.HashMap[Int, String]()
      candidates.foreach { classFile =>
        try {
          val bytes = Files.readAllBytes(classFile)
          val reader = new ClassReader(bytes)
          collectLineNumbers(reader, lines)
          if (firstName.isEmpty) {
            firstName = reader.getClassName()
          }
        } catch {
          case NonFatal(t) =>
            val logger = runner.logger
            logger.error(s"Failed to parse debug line numbers in class file $classFile!")
            logger.trace(t)
        }
      }

      // Returns empty if line wasn't found and first name didn't exist either
      lines.get(line).getOrElse(firstName)
    }
  }

  object VirtualMachineManagerProvider extends IVirtualMachineManagerProvider {
    def getVirtualMachineManager: VirtualMachineManager = Bootstrap.virtualMachineManager
  }
}
