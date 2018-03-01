+++
toc = true
weight = 3
draft = false
title = "The integration guide"
date = "2018-02-09T10:15:00+01:00"
description = "Do you want to learn how to extend or integrate with bloop?"
bref = "Learn how to integrate your own tooling with bloop."
+++

## An overview of integrations

There are two main ways you can extend bloop or integrate it with external
tools. In this document, we dive into them and explain when you would want to
use the first or the latter one.

### Integrate via bloop's model

Bloop has a well-defined, low-level configuration format that describes Scala
and Java projects. This configuration format is the one that all bloop
integrations in other build tools target.

When generated, bloop picks up new files under bloop's project directory and
allows you to run actions on them. External tools can generate and modify the
project model to perform tasks that are not supported by bloop itself.

If you're looking into ways of integrating `coursier`, creating a Ninja
wrapper around bloop or implementing your own low-level build tool, this is
the perfect way to go; nevertheless, make sure you read up on BSP before
finally settling on this way of extending bloop.

### Integrate via the build server protocol

Bloop implements [BSP], a build server protocol that any build tool, IDE or
external tool can implement to talk to bloop's server.

The specification of the protocol is right now in the works, and the Scala
Center is leading this effort in collaboration with major players in the area
of build tools and IDEs (Jetbrains and maintainers of well-known build tools
like sbt, Bazel and Pants).

To grasp what BSP covers, check the work-in-progress specification in
[scalacenter/bsp][BSP]. The recently appointed STP-WG (Scala Tooling Protocol
working group) is also involved in shaping the final specification of bsp.

[BSP]: https://github.com/scalacenter/bsp