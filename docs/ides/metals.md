---
id: metals
title: Metals
sidebar_label: Metals
---

Metals is a work-in-progress language server for Scala that supports a diverse number of text
editors such as Visual Studio Code, `vim`, Sublime Text and Atom.

|                              | Build import | Compile        | Test           | Run            |
| ---------------------------- | ------------ | -------------- | -------------- | -------------- |
| **Metals**                   | ✅           | ✅              | ❌ use the CLI | ❌ use the CLI |

At the moment, the BSP integration only supports build import and compilation (with compiler
diagnostics integrated in the editor). This functionality is enough to provide a fluent developer
experience. Support for test is planned.

![Gif of compiler diagnostics in metals](assets/metals-compiler-diagnostics.gif)

## Installation requirements

1. At least Bloop v1.1.0
1. A build with at least one project in it
1. You use a [build tool supported by Bloop](build-tools/overview.md)

## Install and use Metals

To learn how to install and use Metals in your preferred text editor , head to the [Metals
documentation](https://scalameta.org/metals/docs/editors/overview.html).

## Configure bloop for metals

> This configuration is automatically set by Metals.

Metals requires the [Download dependencies
sources](build-tools/sbt.md#download-dependencies-sources) option enabled in your build for
navigation in external dependencies to work, which is enabled by Metals automatically.

The [Sbt page](build-tools/sbt.md) explains how to configure the build export step but requires you
to add manually bloop's plugin in your build. If you're using Metals, you will need to:

1. Head to the [installation guide](/bloop/setup).
1. Skip the global installation step (click on 'Universal').
1. Select your build tool and add bloop to your build.
1. Follow the build customization you want.

Next time you export with Metals, Metals will respect the configuration in your build.
