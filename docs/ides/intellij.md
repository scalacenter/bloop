---
id: intellij
title: IntelliJ
sidebar_label: IntelliJ
---

IntelliJ is the most widely used Scala and Java IDE. The BSP support that comes
included with the IntelliJ Scala plugin makes it possible to use Bloop together
with IntelliJ.

|                  | Build import | Compile | Test | Run | Debug |
| ---------------- | :----------: | :-----: | :--: | :-: | :---: |
| **IntelliJ BSP** |      ✅      |   ✅    |  ✅  | ✅  |  ✅   |

## Installation requirements

1. At least Bloop v1.1.0
1. A build with at least one project in it
1. You use a [build tool supported by Bloop](build-tools/overview.md)
1. You have followed the [Installation Guide](/bloop/setup), which means:
   - The build server is running in the background.
   - The `bloop` CLI tool is installed in your machine.
   - You have exported your build to Bloop.

## Installation

First, install the latest
[2020.1 IntelliJ IDEA EAP](https://www.jetbrains.com/community/eap/). The 2020.1
version includes several important fixes for the BSP integration that are not
available in the 2019.3 version.

Next, install the IntelliJ Scala plugin and restart IntelliJ. If everything is
configured correctly, the Scala plugin should appear under the list of
"Downloaded" plugins when you open `Preferences > Plugins`.

![Screenshot of the Plugins view showing the Scala plugin is installed](https://i.imgur.com/hBq2DcS.png)

At the time of this writing, the version of the Scala plugin is 2020.1.966.

## Export a Bloop build

> Skip this step if you use sbt since it's handled automatically by IntelliJ.

If you use any other build tool than sbt, you first need to export a Bloop build
following the instructions for your build tool. For an overview of supported
build tools, see [here](../build-tools/overview.md).

## Create new BSP project

After you have exported a Bloop build, run the
`File > New > Project from existing sources` action to create a new IntelliJ
project.

![Screenshot of the "Project from existing sources" action](https://i.imgur.com/HBnTnXg.png)

It's important to not use the "open or import" dialogue since it does not allow
creating a BSP project.

Select a directory that contains the exported Bloop build or root of your sbt
build.

Next, select "BSP" in the "Import project" dialogue.

![Screenshot of the "Import project" dialogue](https://i.imgur.com/tkKzs7F.png)

If everything works correctly, your window should look like this after the BSP
import is complete.

![Screenshot of IntelliJ UI after BSP import is complete](https://i.imgur.com/8fmdv0b.png)

🚀 Congratulations, your build is now imported! If "Build automatically on file
save" is not enabled, you can trigger a bloop compilation by clicking on "Build
| Build project" or its associated keystroke.

## Compile all projects

Run the "Build project" action to compile all Bloop projects.

![Screen recording showing "build project" in action](https://i.imgur.com/qW7bCc9.gif)

Compile errors and warnings are displayed in the "Build output" view with
clickable links to the source location.

![Screenshot of a compile error with a clickable link](https://i.imgur.com/TnNxKlX.png)

## Run tests

Go to a test suite and click "Run 'TEST SUITE NAME'".

![Screenshot showing 'Run TEST SUITE' dialogue](https://i.imgur.com/RBz6EyG.png)

IntelliJ may prompt you to select the BSP target in case the source file belongs
to multiple projects. Select the appropriate project if this prompt comes up. In
most cases, IntelliJ is able to automatically choose the project.

![Screenshot showing "Choose BSP target" prompt](https://i.imgur.com/8n9X5ZP.png)

IntelliJ respects the working directory, system property and other runtime
configuration from Bloop. Note that it's not possible manually configure these
options through the "Edit configurations" window. For example, manual changes
the working directory in a run configuration will be ignored.

## Build on file save

Enable the "build automatically on file save" option to trigger "build project"
as soon as you save the file. First, open the BSP settings by opening the "bsp"
panel on the right and click on the wrench icon.

![Screenshot of the "BSP settings" wrench icon](https://i.imgur.com/QjXwA6o.png)

Next, check the "build automatically on file save" box and select "Apply" to
save the setting.

![Screenshot of the "BSP" settings](https://i.imgur.com/XRwRvdM.png)

This option is helpful to iterate quickly on changes across multiple files. By
default, IntelliJ only shows compile errors in the file that you have open. With
BSP "build automatically on file save", you can see errors in unopened files as
soon as you save the file.

## Refresh project

Run the "Reimport all BSP projects" action to refresh the build. This step is
for example needed when you make changes to `build.sbt` to add a new library
dependency.

## Known limitations

The IntelliJ BSP integration is work-in-progress and therefore has some
well-known limitations.

### No sbt file support

Error highlighting, code completions and navigation don't work in `build.sbt`.

![Screenshot showing error highlighting in build.sbt](https://i.imgur.com/islEgDv.png)

This functionality has not yet been implemented. There is no inherent limitation
in Bloop or BSP that prevents this feature from becoming supported.

### Two-step project refresh for non-sbt builds

The "refresh all BSP projects" action does not re-export the Bloop build for
non-sbt builds. If you are using Gradle, Maven or Mill you need to first trigger
the Bloop export before running the "refresh all BSP projects" action.

The upcoming version of Bloop supports a new `"refreshProjectsCommand"` option
in the Bloop JSON configuration files that makes it possible to re-export the
Bloop build from IntelliJ in one step. Contributions are welcome to implement
this functionality for build tools like Maven, Gradle or Mill.
