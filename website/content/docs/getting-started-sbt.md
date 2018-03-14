+++
description = "The first steps in using Bloop along with sbt"
title = "Using bloop with sbt"
date = "2018-02-09T10:15:00+01:00"
draft = false
weight = 3
bref = "How to use our sbt plugin to generate Bloop's configuration, and how to use Bloop to compile and test your project"
toc = true
+++

### Configuring sbt

The first step is to set up our sbt plugin to generate the configuration files for Bloop. This
action needs to be performed whenever your build changes.

`sbt-bloop` is an `AutoPlugin` that is available for both sbt `0.13` and `1.x`.

To configure `sbt-bloop`, add the following to your `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "<latest-release>")
```

<span class="label warning">Note</span> you need to replace the
`<latest-release>` in the previous snippet by the latest version, which is
[![Latest
version](https://index.scala-lang.org/scalacenter/bloop/sbt-bloop/latest.svg)](https://index.scala-lang.org/scalacenter/bloop/sbt-bloop).

Once sbt is configured, you can run `bloopInstall` to generate the Bloop configuration:

```sh
$ sbt bloopInstall
[info] Loading global plugins from /Users/martin/.sbt/1.0/plugins
(...)
[success] Bloop wrote the configuration of project 'foo' to '/my-project/.bloop-config/foo.config'.
[success] Bloop wrote the configuration of project 'foo-test' to '/my-project/.bloop-config/foo-test.config'.
```

<span class="label warning">Note</span>
This configuration contains machine-dependent information. You should not check
it in your version control.

### Start bloop

If you haven't installed Bloop on your machine yet, please refer to [the installation
instructions]({{< ref "installation.md" >}}).

If you do not already have a Bloop server running, you need to start it before you can start using
Bloop. This is generally not necessary if you have installed Bloop using our Homebrew formula.
Assuming the Bloop server executable is on your `$PATH`, type the following to start the Bloop
server:

```sh
$ blp-server &
```

After the server is started, you can use the Bloop client to communicate with it:

```sh
$ bloop about
       _____            __         ______           __
      / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____
      \__ \/ ___/ __ `/ / __ `/  / /   / _ \/ __ \/ __/ _ \/ ___/
     ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /
    /____/\___/\__,_/_/\__,_/   \____/\___/_/ /_/\__/\___/_/

    bloop-frontend is made with love at the Scala Center <3

    Bloop-frontend version    `1.0.0-M2+14-7134b34d`
    Zinc version     `1.0.0-X8+510-660ed3cb`
    Scala version    `2.12.4`

    It is maintained by Martin Duhem, Jorge Vicente Cantero.
```

```sh
$ bloop projects
Projects loaded from '/my-project/.bloop-config':
 * foo
 * foo-test
```

### Compiling your project with Bloop

You can start compiling your project using the Bloop client:

```sh
$ bloop compile -p foo
Compiling 2 Scala sources to '/my-project/foo/target/classes'...
```

The `compile` command accepts several options that let you configure how compilation happens. To get
more information, type:

```sh
$ bloop compile --help
```

<span class="label warning">Note</span>
All the commands of Bloop support `--help`.

### Testing your project with Bloop

Running the tests with Bloop is performed using the `test` command:
```sh
$ bloop test -p foo
[success] A foo should bar
```

### Next steps

Bloop supports more commands. To get more information about them, continue to the [Commands
reference]({{< ref "commands-reference.md" >}}).
