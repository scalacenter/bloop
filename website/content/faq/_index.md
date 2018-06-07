+++
draft= false
title = "FAQ"
description = "Frequent Answered Questions for Bloop"
+++

{{< partial "edit-on-github.html" >}}

## What is Bloop?

Bloop is a command line tool to speed up edit, compile and test workflows for
Scala developers. Instead of replacing your build tool, Bloop integrates with
it to ensure that core tasks (like compiling and testing) have the shortest
execution time.

[Read the announcement on
scala-lang.org](https://www.scala-lang.org/blog/2017/11/30/bloop-release.html).

## What build tools can be used with Bloop?

At the moment, only sbt and Maven are supported, but we're aiming to support
other popular build tools like Bazel and Gradle. We highly welcome
integrations with other Scala build tools.

## How can I install Bloop?

Please read the [installation instructions]({{< relref "docs/installation.md" >}}).

## Is bloop a new build tool?

Good question! In short, it's not. Read about it in our [Basics]({{< relref
"docs/bloop-basics.md" >}}) guide.

## Where does Bloop write my class files?

Bloop uses a different classes directory than sbt to avoid synchronization
and cache invalidation issues. The classes directories for all projects are
typically stored in the `.bloop/` directory, where every project gets
its own folder.

A classes directory is stored in a similar path as sbt: `target/classes`, and
`target/test-classes` for test projects. If you want to change that, you can
by redefining
[`bloopTargetDir`](https://github.com/scalacenter/bloop/blob/405896f4164cb96bfd39a7369a714d8f73257dd5/integrations/sbt-bloop/src/main/scala/bloop/integrations/sbt/SbtBloop.scala#L49-L50)
in your sbt build like [here](https://github.com/scalacenter/bloop/blob/405896f4164cb96bfd39a7369a714d8f73257dd5/integrations/sbt-bloop/src/main/scala/bloop/integrations/sbt/SbtBloop.scala#L95).

<span class="label warning">Note</span> that you need to scope it in every
sbt project.

## Why does `bloopInstall` trigger compilation or a time-consuming task?

`bloopInstall` is usually a quick task to run, but it can be slow if the classpath
of any of your projects depends on a binary dependency from your build (requiring
`compile` + `publishLocal`) or if you depend on expensive tasks that do source
and resource generation.

You can speed it up by avoiding binary dependencies on modules that your build defines
or by making sure that all the source and resource generators are cached (if you depend
on a plugin, the plugin should make sure of that).

## Bloop doesn't detect some of my sbt configurations!

By default the sbt plugin exports only `Compile` and `Test` configurations.
If you want to export other sbt configurations too, please read about
[advanced sbt configuration]({{<relref "docs/sbt.md" >}}).

## Is Bloop open source?

Yes. Bloop is released under the Apache-2.0 license and is free and open source software.

## Who is behind Bloop?

Bloop is an initiative of the [Scala Center](https://scala.epfl.ch).
