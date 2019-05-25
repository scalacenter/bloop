package bloop

import java.io.File
import bloop.io.AbsolutePath

/**
 * A compiler oracle is an entity that provides answers to questions that come
 * up during the compilation of build targets. The oracle is an entity capable
 * of synchronizing and answering questions critical for deduplicating and
 * running compilations concurrently.
 *
 * For example, if a project wants to know something about the compilation of
 * its dependencies, the oracle would be the right place to create a method
 * that provides answers.
 *
 * The compiler oracle is created every time a project compilation is
 * scheduled. Depending on the implementation, it can know both global
 * information such as all the ongoing compilations happening in the build
 * server, local data such as how a target is being compiled or both.
 */
abstract class CompilerOracle {

  /**
   * Returns java sources of all those dependent projects whose compilations
   * are not yet finished when build pipelining is enabled. If build pipelining
   * is disabled, returns always an empty list since the class files of Java
   * sources are already present in the compilation classpath.
   */
  def askForJavaSourcesOfIncompleteCompilations: List[File]

  /**
   * Registers a macro defined during this compilation run. It takes a full
   * symbol name and associates it with the project under compilation.
   */
  def registerDefinedMacro(definedMacroSymbol: String): Unit

  /**
   * Blocks until the macro classpath for this macro is ready. If the macro has
   * not been defined, we ignore it (it comes from a third-party library),
   * otherwise we will wait until all dependent projects defining macros have
   * finished compilation.
   */
  def blockUntilMacroClasspathIsReady(usedMacroSymbol: String): Unit

  /**
   * Starts downstream compilations with the compile pickle data generated
   * during the compilation of a project. This method needs to take care of
   * making the pickles accessible to downstream compilations.
   */
  def startDownstreamCompilations(picklesDir: AbsolutePath, pickles: List[ScalaSig]): Unit
}
