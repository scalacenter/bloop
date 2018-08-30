package sbt.internal.inc.bloop.internal

/**
 * Defines a stop point for pipelined compilation.
 *
 * Pipelining forces the compilation of dependent modules while dependent modules are being
 * compiled. If there is an error in any of the previous Scala projects, the compilation
 * of the projects that depend on the failed project need to fail fast.
 *
 * `StopPipelining` is the way to stop pipelined compilation from the guts of Zinc. We throw
 * this exception from deep inside `BloopHighLevelCompiler`, and then we catch it in
 * `bloop.Compiler` and translate it to a `Compiler.Blocked` result.
 */
final class StopPipelining(val failedProjectNames: List[String])
    extends Exception(s"Pipelining stopped, projects ${failedProjectNames} failed to compile.")
