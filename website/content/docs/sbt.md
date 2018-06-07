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
by by permanently adding them to your build file, or by temporarily setting
them in your sbt shell.

### Permanent solution: `*.sbt` file

(This is the **recommended** solution.)

Let's say that our project has
[an `IntegrationTest` configuration](https://www.scala-sbt.org/1.0/docs/offline/Testing.html#Integration+Tests).

To tell bloop to export it we need to add the following line in our `build.sbt` file:

```scala
import bloop.integrations.sbt.BloopDefaults

// Dummy sbt project with an IntegrationTest configuration
val foo = project
  .configs(IntegrationTest)
  .settings(
    // This is the line bloop requires
    inConfig(IntegrationTest)(BloopDefaults.configSettings)
  )
```

After reloading your build, `bloopInstall` will export a configuration file
for the `IntegrationTest` configuration called `foo-it.json`:

```
sbt> bloopInstall
[info] Loading global plugins from /home/user/.sbt/1.0/plugins
(...)
[success] Generated '/my-project/.bloop/foo.json'.
[success] Generated '/my-project/.bloop/foo-test.json'.
[success] Generated '/my-project/.bloop/foo-it.json'.
```

If you don't want to pollute your main `build.sbt` file with Bloop-specific
settings, you can add the previous `inConfig` line in another file
(e.g. `local.sbt`) and add this file to your global `.gitignore`
(or some other VCS-specific ignore file if not using Git).

### Temporary solution: sbt shell

To export a configuration temporarily or make experiments, type the following
in your sbt shell:

```
sbt> set inConfig(IntegrationTest)(bloop.integrations.sbt.BloopDefaults.configSettings)
```

where `IntegrationTest` is the configuration which you want to export. Note that
you may need to scope it in the project that contains the `IntegrationTest`
configuration. Now you can run `bloopInstall` as usual:

```
sbt> bloopInstall
[info] Loading global plugins from /home/user/.sbt/1.0/plugins
(...)
[success] Generated '/my-project/.bloop/foo.json'.
[success] Generated '/my-project/.bloop/foo-test.json'.
[success] Generated '/my-project/.bloop/foo-it.json'.
```

These settings *will be lost after you exit or reload sbt*, so if you need
to regenerate Bloop configuration files later on you will either need to run
the `set` command again or use the permanent recommend solution above.
