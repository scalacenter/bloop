package bloop
import bloop.io.AbsolutePath

/**
 * Collects the partial compile products generated after typechecking but
 * before the end of compilation of a project.
 */
case class PartialCompileProducts(
    internalNewClassesDir: AbsolutePath,
    internalNewPicklesDir: AbsolutePath
)
