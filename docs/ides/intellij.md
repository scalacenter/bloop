---
id: intellij
title: IntelliJ
sidebar_label: IntelliJ
---

IntelliJ is the most popular Scala and Java IDE. It integrates with Bloop via a work-in-progress BSP
integration with varying degree of functionality.

|                              | Build import | Compile        | Test                  | Run                  | Debug                 |
| ---------------------------- | ------------ | -------------- | --------------------- | -------------------- | --------------------- |
| **IntelliJ BSP**             | ✅           | ✅             | 🚧 use IntelliJ test | 🚧 use IntelliJ test | 🚧 use IntelliJ test |

At the moment, the BSP integration only supports build import, compiler diagnostics and compilation
on file save. This functionality is enough to benefit from a shorter and more accurate developer
workflow. Support for test is planned.

## Installation requirements

1. At least Bloop v1.1.0
1. A build with at least one project in it
1. You use a [build tool supported by Bloop](build-tools/overview.md)
1. You have followed the [Installation Guide](/bloop/setup), which means:
    * The build server is running in the background.
    * The `bloop` CLI tool is installed in your machine.
    * You have exported your build to Bloop.

## Install the experimental bsp integration

1. Install the latest [2018.3 EAP IntelliJ version](https://www.jetbrains.com/community/eap/)
1. Enable the nightly of the Scala plugin in "Preferences | Languages & Frameworks | Scala | Updates".
   ![](assets/intellij-nightly-plugin.png)
1. Click on the "Check for Updates" button next to the "Plugin Update Channel".
1. Accept IntelliJ's prompt to download and install the Scala nightly release.
1. Reboot IntelliJ to reload the plugin. The Scala plugin **should now have version 2018.3.530** or higher.
1. Open the "Find Action" search box (<kbd>Shift</kbd> + <kbd>Ctrl</kbd> + <kbd>A</kbd> or
<kbd>Shift</kbd> + <kbd>Cmd</kbd> + <kbd>A</kbd>) and type "bsp". An option called "Enable
experimental bsp support" will show up. Click on it.

The experimental BSP support has now been enabled.

## Import project via bloop

1. Click on "Import Project", select your project workspace and click OK. A window pops up with all
   the supported import options. Pick "bsp" and click on "Next". ![](assets/intellij-bsp-import.png)

1. The next window shows you the bsp import settings.
   ![](assets/intellij-bsp-configure.png)

   1. If you're a Mac OS/Windows users, find out the bloop binary location (`which bloop` in Mac OS
   / `where bloop*` in Windows) and set it in the "Bloop executable" field of "Global settings".
   1. If you want to "Build automatically on file save", check the box above "Project format".

1. Click "Finish". IntelliJ will then load the project. At the end of the process, you should see:
   ![](assets/intellij-imported-project.png)

🚀 Congratulations, your build is now imported! If "Build automatically on file save" is not
enabled, you can trigger a bloop compilation by clicking on "Build | Build project" or its
associated keystroke.

## Known limitations

The IntelliJ BSP integration is work-in-progress and therefore has some well-known limitations.

### Manual bloop export project

IntelliJ does not run `bloopInstall` whenever it detects a change in sbt build files like
[Metals](build-tools/metals.md) does. Therefore, if you want to re-import your project the following
two steps are required:

1. Export your project to Bloop manually.
1. Click on "Refresh project" as you would normally do to refresh your project.

### Limited "Go to Definition" in external dependencies

"Go to Definition" in external dependencies only works in sbt projects when the [Download
dependencies sources](build-tools/sbt.md#download-dependencies-sources) option is enabled.
At the moment, this option is not supported in the rest of the supported build tools.

Note that this only navigation in external dependencies is affected. Navigating your project
dependencies should work as expected.
