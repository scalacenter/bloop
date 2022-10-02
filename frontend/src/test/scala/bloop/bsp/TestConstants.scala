package bloop.bsp

import bloop.internal.build.BuildInfo

object TestConstants {
  val buildInitialize: String = raw"""
  {
    "result": {
      "displayName": "${BuildInfo.bloopName}",
      "version": "${BuildInfo.version}",
      "bspVersion": "${BuildInfo.bspVersion}",
      "capabilities": {
        "compileProvider": { "languageIds": ["scala", "java"] },
        "testProvider": { "languageIds": ["scala", "java"] },
        "runProvider": { "languageIds": ["scala", "java"] },
        "debugProvider": { "languageIds": ["scala", "java"] },
        "inverseSourcesProvider": true,
        "dependencySourcesProvider": true,
        "resourcesProvider": true,
        "buildTargetChangedProvider": false,
        "jvmTestEnvironmentProvider": true,
        "jvmRunEnvironmentProvider": true,
        "canReload": false
      }
    },
    "id": 2,
    "jsonrpc": "2.0"
  }
  """.replaceAll("\\s", "")
}
