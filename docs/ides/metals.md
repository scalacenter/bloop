---
id: metals
title: Metals
sidebar_label: Metals
---

Metals is a language server for Scala that supports a diverse number of text
editors such as Visual Studio Code, Vim, Emacs, Eclipse, Sublime Text and Atom.

|                              | Build import | Compile        | Test                  | Run                  | Debug                 |
| ---------------------------- | ------------ | -------------- | --------------------- | -------------------- | --------------------- |
| **Metals**                   | ✅           | ✅             | ✅                    | ✅                   | ✅                    |


The BSP integration in metals supports build import, compilation, Test, Run, and
Debug functionality.

![Gif of compiler diagnostics in metals](assets/metals-compiler-diagnostics.gif)

## Installation requirements

If you don't have Bloop installed locally, Metals will automatically start a
Bloop server for you. However, if you'd like to utilize Bloop CLI, you'll need
to ensure you have [Bloop installed](/bloop/setup).

To ensure that Bloop is working correctly you'll want to make sure:

1. Your build has at least one project in it.
2. You use a [build tool supported by Bloop](build-tools/overview.md)

## Install and use Metals

To learn how to install and use Metals in your preferred text editor, head to the [Metals
documentation](https://scalameta.org/metals/docs/editors/overview.html).

## Configure bloop for metals

> This configuration is automatically set by Metals.

Metals requires the [Download dependencies
sources](build-tools/sbt.md#download-dependencies-sources) option enabled in your build for
navigation in external dependencies to work, which is enabled by Metals automatically.

The [Sbt page](build-tools/sbt.md) explains how to configure the build export
step but requires you to manually add bloop's plugin in your build. However, if
you're using Metals, the `sbt-bloop` plugin will automatically be added to your
build ensuring that when you export your build, the configuration will be
respected.
