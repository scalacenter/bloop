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
[`bloopTargetDir`](https://github.com/scalacenter/bloop/blob/6e1d55cc840905c475d4e97eaf443fdacfcf1e34/integrations/sbt-bloop/src/main/scala/bloop/integrations/sbt/SbtBloop.scala#L26-L27)
in your sbt build like [here](https://github.com/scalacenter/bloop/blob/6e1d55cc840905c475d4e97eaf443fdacfcf1e34/integrations/sbt-bloop/src/main/scala/bloop/integrations/sbt/SbtBloop.scala#L59).

<span class="label warning">Note</span> that you need to scope it in every
sbt project.

## Is Bloop open source?

Yes. Bloop is released under the Apache-2.0 license and is free and open source software.

## Who is behind Bloop?

Bloop is an initiative of the [Scala Center](https://scala.epfl.ch).
