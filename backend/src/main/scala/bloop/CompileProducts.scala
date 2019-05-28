package bloop

import java.io.File
import java.nio.file.Path

import xsbti.compile.PreviousResult

/**
 * Collects the products generated during the compilation of a project.
 *
 * It has two previous results. The first is to be used by the projects
 * that depend on this compilation and are being compiled during this run. This
 * result is used because it contains class file locations referring to both
 * classes directories and because these two classes directories will be in the
 * dependency classpath of dependent compilations.
 *
 * The latter previous result is used for future client compilations. Future
 * compilations will not see the `readOnlyClassesDir` and `newClassesDir` in
 * this abstraction, but instead see an aggregated read-only classes directory.
 * `newClassesDir` will become this read-only classes directory by the time of
 * that compilation because all compilation products (class files, semanticdb
 * files, sjsir files, tasty files, nir files, etc) in the current
 * `readOnlyClassesDir` that are not in `newClassesDir` will be copied over.
 * Therefore,the result that is used for compilation will have all class file
 * references rebased to `newClassesDir`.
 *
 * We use different previous results because we want to remove the cost of
 * copying class files from `readOnlyClassesDir` to `newClassesDir` before
 * starting dependent compilations. This scheme allows us to be fast and
 * perform as much IO work in the background as possible.
 */
case class CompileProducts(
    readOnlyClassesDir: Path,
    newClassesDir: Path,
    resultForDependentCompilationsInSameRun: PreviousResult,
    resultForFutureCompilationRuns: PreviousResult,
    invalidatedCompileProducts: Set[File],
    generatedRelativeClassFilePaths: Map[String, File],
    definedMacroSymbols: Array[String]
)
