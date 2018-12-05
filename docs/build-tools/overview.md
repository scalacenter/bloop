---
id: overview
title: Overview
sidebar_label: Overview
---

Bloop supports several build tools with varying degree of functionality.

|                          | sbt        | Gradle   | Maven    | Mill       | Bazel | Pants | Fury |
| ------------------------ | ---------- | -------- | -------- | ---------- | ----- | ----- | ---- |
| **Build Export**         | ✅         | ✅        | ✅       | ✅         |  ❌    |   ❌  |    ✅   |
| **Built-in Compile / Test / Run** | 📅 planned  |          |          |            |       |       | ✅   |

## Build Export

The most basic integration is **Build Export**. Build export is a task in your build tool that
translates your build definitions to Bloop configuration files, which are used by Bloop to compile,
test and run your Scala code. It provides all you need to get started with Bloop.

Exporting your build is supported by a large array of popular Scala and Java build tools. However,
it's a tedious process that users must remember to run whenever their build changes (client
integrations such as [Metals](https://metals.rocks) can export the build automatically, but that's
usually not the case if you're interfacing directly with the bloop CLI).

## Built-in compile, test and run

**Built-in compile, test and run** is a richer build tool integration that swaps your build tool's
implementation of `compile`, `test` and `run` by their implementation in Bloop. This integration
relieves users from exporting the build (build export is automatic) and instead provides a
transparent build tool integration users are unaware of.

Whereas built-in integrations are not fully developed yet, a built-in build tool integration for sbt
is planned for the next release. These integrations will help users benefit from the speed and
reliability of a more modern Scala toolchain with minimum interference in their current workflow.
