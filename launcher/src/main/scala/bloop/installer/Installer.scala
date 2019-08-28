package bloop.installer

import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Path

class InstallerMain(
    in: InputStream,
    out: PrintStream,
    err: PrintStream,
    version: String,
    nailgunVersion: String,
    coursierVersion: String,
    installationDir: Path,
    ivyHome: Path,
    bloopHome: Path
) {}
