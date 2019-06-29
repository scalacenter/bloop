package bloop.dap

import java.util
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

import com.microsoft.java.debug.core.IEvaluatableBreakpoint
import com.microsoft.java.debug.core.adapter._
import com.microsoft.java.debug.core.protocol.Types
import com.sun.jdi.{ObjectReference, StackFrame, ThreadReference, Value}
import io.reactivex.Observable

object DebugExtensions {
  def newContext: IProviderContext = {
    val context = new ProviderContext
    context.registerProvider(classOf[IHotCodeReplaceProvider], HotCodeReplaceProvider)
    context.registerProvider(classOf[IVirtualMachineManagerProvider], VirtualMachineManagerProvider)
    context.registerProvider(classOf[ISourceLookUpProvider], SourceLookUpProvider)
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
    override def onClassRedefined(consumer: Consumer[util.List[String]]): Unit = {}
    override def redefineClasses(): CompletableFuture[util.List[String]] =
      CompletableFuture.completedFuture(util.Collections.emptyList())
    override def getEventHub: Observable[HotCodeReplaceEvent] = Observable.empty()
  }

  object SourceLookUpProvider extends ISourceLookUpProvider {
    override def supportsRealtimeBreakpointVerification(): Boolean = false

    override def getFullyQualifiedName(
        uri: String,
        lines: Array[Int],
        columns: Array[Int]
    ): Array[String] = Array()

    override def getSourceFileURI(fullyQualifiedName: String, sourcePath: String): String =
      sourcePath
    override def getSourceContents(uri: String): String = ""
  }
}
