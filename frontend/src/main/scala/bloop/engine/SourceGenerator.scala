package bloop.engine

import java.nio.file.FileSystems

import scala.collection.mutable
import scala.util.control.NoStackTrace

import bloop.cli.CommonOptions
import bloop.config.Config
import bloop.data.SourcesGlobs
import bloop.exec.Forker
import bloop.io.AbsolutePath
import bloop.io.ByteHasher
import bloop.io.Paths
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.task.Task

final case class SourceGenerator(
    cwd: AbsolutePath,
    sourcesGlobs: List[SourcesGlobs],
    outputDirectory: AbsolutePath,
    unmangedInputs: List[AbsolutePath],
    command: List[String]
) {

  /**
   * Run this source generator if needed.
   *
   * If the inputs and outputs of this generator are unchanged, then this is a no-op.
   *
   * @param previous The previous known state of this source generator.
   * @param logger The logger to report source generator messages.
   * @return The new known inpouts and outputs of this source generator.
   */
  def update(
      previous: SourceGenerator.Run,
      logger: Logger,
      opts: CommonOptions
  ): Task[SourceGenerator.Run] =
    needsUpdate(previous).flatMap {
      case SourceGenerator.NoChanges =>
        Task.now(previous)
      case SourceGenerator.InputChanges(newInputs, newUnmanagedInputs) =>
        logger.debug("Changes detected to inputs of source generator")(DebugFilter.Compilation)
        run(newInputs, newUnmanagedInputs, logger, opts)
      case SourceGenerator.OutputChanges(inputs, unmanagedInputs) =>
        logger.debug("Changes detected to outputs of source generator")(DebugFilter.Compilation)
        run(inputs, unmanagedInputs, logger, opts)
    }

  /**
   * The list of sources that this source generator consumes.
   *
   * @return The list of sources that this source generator consumes.
   */
  def getSources: Task[List[AbsolutePath]] = Task {
    val buf = mutable.ListBuffer.empty[AbsolutePath]
    for (glob <- sourcesGlobs) glob.walkThrough(buf += _)
    buf.result()
  }

  private def run(
      inputs: Map[AbsolutePath, Int],
      unmangedInputs: Map[AbsolutePath, Int],
      logger: Logger,
      opts: CommonOptions
  ): Task[SourceGenerator.Run] = {
    val cmd = (command :+ outputDirectory.syntax) ++ inputs.keys.map(_.syntax)
    logger.debug { () =>
      cmd.mkString(s"Running source generator:${System.lineSeparator()}$$ ", " ", "")
    }

    Forker.run(cwd, cmd, logger, opts).flatMap {
      case 0 =>
        hashOutputs.map { SourceGenerator.PreviousRun(inputs, _, unmangedInputs) }
      case exitCode =>
        Task.raiseError(new SourceGenerator.SourceGeneratorException(exitCode))
    }
  }

  private def needsUpdate(previous: SourceGenerator.Run): Task[SourceGenerator.Changes] = {
    previous match {
      case SourceGenerator.NoRun =>
        Task.zip2(hashInputs, hashUnamanagedInputs).map {
          case (inputs, unmanagedInputs) =>
            SourceGenerator.InputChanges(inputs, unmanagedInputs)
        }
      case SourceGenerator.PreviousRun(inputs, outputs, unmanagedInputs) =>
        Task.zip2(hashInputs, hashUnamanagedInputs).flatMap {
          case (newInputs, newUnmanagedInputs) =>
            if (newInputs != inputs || newUnmanagedInputs != unmanagedInputs)
              Task.now(SourceGenerator.InputChanges(newInputs, newUnmanagedInputs))
            else {
              hashOutputs.map { newOutputs =>
                if (newOutputs != outputs)
                  SourceGenerator.OutputChanges(newInputs, newUnmanagedInputs)
                else
                  SourceGenerator.NoChanges
              }
            }
        }
    }
  }

  private def hashInputs: Task[Map[AbsolutePath, Int]] = {
    for (inputs <- getSources) yield hashFiles(inputs)
  }

  private def hashUnamanagedInputs: Task[Map[AbsolutePath, Int]] = Task {
    hashFiles(unmangedInputs)
  }

  private def hashOutputs: Task[Map[AbsolutePath, Int]] = Task {
    val outputs = Paths.pathFilesUnder(outputDirectory, "glob:**")
    hashFiles(outputs)
  }

  private def hashFiles(files: List[AbsolutePath]): Map[AbsolutePath, Int] =
    files.map(f => f -> ByteHasher.hashFileContents(f.toFile)).toMap
}

object SourceGenerator {

  sealed trait Run
  case object NoRun extends Run
  case class PreviousRun(
      knownInputs: Map[AbsolutePath, Int],
      knownOutputs: Map[AbsolutePath, Int],
      knownUnmanagedInputs: Map[AbsolutePath, Int]
  ) extends Run

  private sealed trait Changes
  private case object NoChanges extends Changes
  private case class InputChanges(
      newInputs: Map[AbsolutePath, Int],
      unamanagedInputs: Map[AbsolutePath, Int]
  ) extends Changes
  private case class OutputChanges(
      inputs: Map[AbsolutePath, Int],
      unamanagedInputs: Map[AbsolutePath, Int]
  ) extends Changes

  def fromConfig(cwd: AbsolutePath, generator: Config.SourceGenerator): SourceGenerator = {
    val sourcesGlobs = generator.sourcesGlobs.map {
      case Config.SourcesGlobs(directory, depth, includes, excludes) =>
        val fs = FileSystems.getDefault
        val includeMatcher = includes.map(fs.getPathMatcher)
        val excludeMatcher = excludes.map(fs.getPathMatcher)
        SourcesGlobs(
          AbsolutePath(directory),
          depth.getOrElse(Int.MaxValue),
          includeMatcher,
          excludeMatcher
        )
    }
    new SourceGenerator(
      cwd,
      sourcesGlobs,
      AbsolutePath(generator.outputDirectory),
      generator.unmanagedInputs.map(AbsolutePath.apply),
      generator.command
    )
  }

  class SourceGeneratorException(exitCode: Int)
      extends RuntimeException(s"Source generator failed (non-zero exit code: $exitCode).")
      with NoStackTrace
}
