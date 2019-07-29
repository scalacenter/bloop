package bloop.data

/**
 * A partial loaded build is the incremental result of loading a certain amount
 * of configuration files from disk and post-processing them in-memory.
 */
case class PartialLoadedBuild(
    projects: List[LoadedProject]
)
