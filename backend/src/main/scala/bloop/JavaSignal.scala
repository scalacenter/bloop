package bloop

/**
 * Signals the Zinc internals whether Java compilation should be attempted or not.
 *
 * This signal is completed by Bloop when all the Scala compilations of dependent projects
 * have been successful. If they haven't, then the java signal will be `FailFastCompilation`
 * with the list of the projects that failed.
 *
 * The reason why we need a java signal is because pipelining may start the compilation
 * of dependent projects earlier than the compilation of the dependent projects have
 * compiled. This is okay for dependent Scala projects because they can be compiled
 * against the public interfaces extracted from the Scala pickles, but it's not okay for
 * the java compilation because Java code may have dependencies on Scala code, and for
 * Javac to compile successfully it needs to read the Scala code public APIs from class files
 * (which means the dependent projects need to have finished successfully).
 */
sealed trait JavaSignal

object JavaSignal {
  case object ContinueCompilation extends JavaSignal
  final case class FailFastCompilation(failedProjects: List[String]) extends JavaSignal
  object FailFastCompilation {
    def apply(failedProject: String): FailFastCompilation = FailFastCompilation(List(failedProject))
  }
}
