+++
title = "Advanced sbt configuration"
description = "How to use Bloop in sbt projects"
bref = "Learn advanced configuration of Bloop's sbt plugin"
date = "2018-06-06T23:39:00+02:00"
draft = false
weight = 5
toc = true
+++

## Custom project configurations

By default Bloop generates its project files only for the standard `Compile`
(`project.json`) and `Test` (`project-test.json`) sbt configurations. If your
sbt project defines other configurations like `IntegrationTest`, you might want
to export them as Bloop projects too.

In order for Bloop to export your custom configurations, they need to include
some
[Bloop-specific settings and tasks](https://github.com/scalacenter/bloop/blob/405896f4164cb96bfd39a7369a714d8f73257dd5/integrations/sbt-bloop/src/main/scala/bloop/integrations/sbt/SbtBloop.scala#L80-L89),
of which the most important is the `bloopGenerate` task. You can do this either
by temporarily setting them in your sbt shell, or by permanently adding them to
your build file.

### Temporary solution: sbt shell

In your sbt shell, type:

```
sbt> set inConfig(IntegrationTest)(bloop.integrations.sbt.BloopDefaults.configSettings)
```

where `IntegrationTest` is the configuration which you want to export. Now you
can run `bloopInstall` as usual:

```
sbt> bloopInstall
[info] Loading global plugins from /home/user/.sbt/1.0/plugins
(...)
[success] Generated '/my-project/.bloop/foo.json'.
[success] Generated '/my-project/.bloop/foo-test.json'.
[success] Generated '/my-project/.bloop/foo-it.json'.
```

Please note that these settings will be lost after you exit sbt, so if you need
to regenerate Bloop configuration files later you will need to run the `set`
command above again.

### Permanent solution: `*.sbt` file

You can also add the relevant settings to an `*.sbt` file in your project's
root directory. Normally, that would be `build.sbt`, but if you don't want to
pollute your main build file with Bloop-specific settings, you can create
another one (e.g. `local.sbt`) and add this file to your `.gitignore` (or some
other VCS-specific ignore file if not using Git).

The code you need to add (again, assuming `IntegrationTest` is the sbt
configuration you want to export):

```scala
import bloop.integrations.sbt.BloopDefaults

inConfig(IntegrationTest)(BloopDefaults.configSettings)
```
