package bloop.reporter

import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.logging.Logger

case class ReporterInputs[UseSiteLogger <: Logger](
    project: Project,
    cwd: AbsolutePath,
    logger: UseSiteLogger
)
