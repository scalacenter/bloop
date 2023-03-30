package bloop.cli

import caseapp.core.RemainingArgs

import bloop.cli.options.OutputOptions
import bloop.rifle.BloopRifleConfig
import caseapp.core.app.Command

object Output extends Command[OutputOptions] {
  override def names = List(List("output"))

  def run(options: OutputOptions, args: RemainingArgs): Unit = {
    val logger           = options.logging.logger
    val bloopRifleConfig = options.bloopRifleConfig
    val outputFile = bloopRifleConfig.address match {
      case s: BloopRifleConfig.Address.DomainSocket =>
        logger.debug(s"Bloop server directory: ${s.path}")
        logger.debug(s"Bloop server output path: ${s.outputPath}")
        os.Path(s.outputPath, os.pwd)
      case tcp: BloopRifleConfig.Address.Tcp =>
        if (options.logging.verbosity >= 0)
          System.err.println(
            s"Error: Bloop server is listening on TCP at ${tcp.render}, output not available."
          )
        sys.exit(1)
    }
    if (!os.isFile(outputFile)) {
      if (options.logging.verbosity >= 0)
        System.err.println(s"Error: $outputFile not found")
      sys.exit(1)
    }
    val content = os.read.bytes(outputFile)
    logger.debug(s"Read ${content.length} bytes from $outputFile")
    System.out.write(content)
  }
}
