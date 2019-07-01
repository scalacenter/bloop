package buildpress

import bloop.io.AbsolutePath
import buildpress.config.Config.BuildSettingsHashes

final case class ClonedRepository(
    metadata: Repository,
    localPath: AbsolutePath,
    buildSettingsHashes: BuildSettingsHashes
)
