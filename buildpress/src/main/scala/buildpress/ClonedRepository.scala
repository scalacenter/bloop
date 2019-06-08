package buildpress

import buildpress.config.Config.BuildSettingsHashes
import buildpress.io.AbsolutePath

final case class ClonedRepository(
    metadata: Repository,
    localPath: AbsolutePath,
    buildSettingsHashes: BuildSettingsHashes
)
