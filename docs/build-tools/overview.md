---
id: overview
title: Overview
sidebar_label: Overview
---

Bloop supports several build tools with varying degree of functionality.

| Build tool |  **Build export**   |
| ---------- | :-----------------: |
| sbt        |         ✅          |
| Gradle     |         ✅          |
| Maven      |         ✅          |
| Mill       |         ✅          |
| Bazel      |         ❌          |

## Build Export

The most basic integration is **Build Export**. Build export is a task in your
build tool that translates your build definitions to Bloop configuration files,
which are used by Bloop to compile, test and run your Scala code. It provides
all you need to get started with Bloop.

Exporting your build is supported by a large array of popular Scala and Java
build tools. However, it's a tedious process that users must remember to run
whenever their build changes (client integrations such as
[Metals](<https://scalameta.org/metals/> or [Scala CLI](http://scala-cli.virtuslab.org)) can export the build automatically, but that's
usually not the case if you're interfacing directly with the bloop CLI).
