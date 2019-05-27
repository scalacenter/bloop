package bloop

import bloop.io.AbsolutePath

/**
 * Collects the partial compile products generated after typechecking but
 * before the end of compilation of a project.
 */
case class PartialCompileProducts(
    readOnlyClassesDir: AbsolutePath,
    newClassesDir: AbsolutePath,
    newPicklesDir: AbsolutePath
)
