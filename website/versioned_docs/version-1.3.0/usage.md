---
id: version-1.3.0-usage
title: Quickstart
sidebar_label: Quickstart
original_id: usage
---

## Requirements

1. A build with some projects in it (the guide assumes `foo` and `foo-test`)
2. You have followed the [Installation Guide](/bloop/setup), which means:
   * The build server is running in the background.
   * The `bloop` CLI tool is installed in your machine.
   * You have exported your build to Bloop.

## Compile

Let's compile first the test project from scratch.

```bash
→ bloop compile foo-test
Compiling foo (1 Scala source)
Non-compiled module 'compiler-bridge_2.12' for Scala 2.12.7. Compiling...
  Compilation completed in 12.165s.
Compiled foo (13176ms)
Compiling foo-test (1 Scala source)
Compiled foo-test (578ms)
```

The first time you compile, Bloop resolves and compiles the compilation bridges for your Scala
version if they are not found in the bloop cache. Then, it follows up with the incremental
compilation.

## Compile downstream projects

The compilation of a project always requires the compilation of all downstream projects (its
dependencies).

The previous logs show Bloop has compiled both `foo-test` and `foo`. This is expected given that
`foo` is a dependency of `foo-test` and `foo` wasn't already compiled.

## Compile upstream projects

The `--cascade` flag allows you to change the public signature of a project (say, `foo`) and have
bloop compile all the transitive projects depending on `foo` to detect compilation errors.

Assuming we have not compiled `foo` and `foo-test` before, cascade compilation of `foo` will compile
`foo` and projects depending on `foo`; in our build, only `foo-test`.

```bash
→ bloop compile --cascade foo
Compiling foo (1 Scala source)
Compiled foo (13176ms)
Compiling foo-test (1 Scala source)
Compiled foo-test (578ms)
```

We say that cascade compilation compiles projects upstream because it's the inverse of compiling
projects downstream. Use `--cascade` whenever you know you're going to work in a subset of your
build graph but you don't remember the projects that are part of it.

## Clean the caches

Clean the compilation caches in your build with:

```bash
→ bloop clean foo-test
→
```

This operation is uncommon but might be useful if the incremental compiler state is causing spurious
errors. `clean` does not remove class files, it only cleans the incremental compilation state. If
you want to remove the class files, do it manually.

By default, `bloop clean` only cleans the cache of a given project. Run `clean` with `--propagate`
to clean the cache of downstream projects.

```bash
→ bloop clean foo-test --propagate
→
```

Compiling again `foo-test` should trigger the compilation of both `foo` and `foo-test`.

```bash
→ bloop compile foo-test
Compiling foo (1 Scala source)
Compiled foo (290ms)
Compiling foo-test (1 Scala source)
Compiled foo-test (455ms)
```

## Test

Once `foo` and `foo-test` are compiled, let's test them:

```bash
→ bloop test foo
CubeCalculatorTest:
- CubeCalculator.cube
Execution took 19ms
1 tests, 1 passed
All tests in CubeCalculatorTest passed

===============================================
Total duration: 19ms
All 1 test suites passed.
===============================================
```

We use `bloop test foo` instead of `bloop test foo-test` out of habit even though only `foo-test`
defines test cases. In fact, Bloop interprets `bloop test foo` as a shortcut for `bloop test
foo-test` if `foo-test` exists in the build.

## Test downstream projects

`bloop test` only runs tests of a concrete project. You can run tests for your project and all your
dependencies with the `--propagate` flag:

```bash
→ bloop test foo --propagate
CubeCalculatorTest:
- CubeCalculator.cube
Execution took 16ms
1 tests, 1 passed
All tests in CubeCalculatorTest passed

===============================================
Total duration: 16ms
All 1 test suites passed.
===============================================
```

As expected, the output is the same as `bloop test foo` because `foo` has no dependencies defining
test sources.

## Test upstream projects

Like [`compile foo --cascade`](#compile-upstream-projects), `test foo --cascade`:

1. triggers the compilation of `foo` and all projects depending on `foo` (with their dependencies)
2. runs tests of `foo` and all projects depending on `foo` (without their dependencies)

In our case, testing `foo` with or without `--cascade` will compile and test the same number of
projects because our build graph is simple enough and only `foo-test` defines tests.

```bash
→ bloop test foo --cascade
CubeCalculatorTest:
- CubeCalculator.cube
Execution took 16ms
1 tests, 1 passed
All tests in CubeCalculatorTest passed

===============================================
Total duration: 16ms
All 1 test suites passed.
===============================================
```

However, given:

1. two new projects `bar` and `bar-test`
2. two dependencies;
   * one between `foo-test` and `bar-test`
   * another one between `bar-test` and `bar`

`test foo --cascade` will:

1. compile `foo`, `foo-test`, `bar` and `bar-test`
2. test `foo` (skipping it, as it has no tests) and `foo-test`

```bash
→ bloop test foo --cascade
CubeCalculatorTest:
- CubeCalculator.cube
Execution took 16ms
1 tests, 1 passed
All tests in CubeCalculatorTest passed

===============================================
Total duration: 16ms
All 1 test suites passed.
===============================================
```

Enabling `--cascade` for test execution is a powerful tool to run tests on all projects that could
be possibly affected by a change in a project.

## Test only a specific test suite

You can test only a specific test suite with `--only` or `-o`:

```bash
→ bloop test foo -o CubeCalculatorTest
CubeCalculatorTest:
- CubeCalculator.cube
Execution took 17ms
1 tests, 1 passed
All tests in CubeCalculatorTest passed

===============================================
Total duration: 17ms
All 1 test suites passed.
===============================================
```

Note that:

1. You can repeat the `-o` test arguments as many times you want to run
   different test suite at once.
2. You can use `*` as part of the test suite name to match more than one
   suite directly.

## Pass test arguments to test

Pass arguments to the test framework after `--`:

```bash
→ bloop test foo -- -h /disk/foo/target/html
CubeCalculatorTest:
- CubeCalculator.cube
Execution took 11ms
1 tests, 1 passed
All tests in CubeCalculatorTest passed

===============================================
Total duration: 11ms
All 1 test suites passed.
===============================================
```

Test framework arguments are unique per test frameworks and parsed independently. Passing arguments
specific to one test framework will work only if:

1. Your test argument is a system property (`-Dkey=value`)
2. Your test project uses only one test framework
3. You have a project with tests of different test frameworks (e.g. Scalacheck and Scalatest) but
   you're filtering out tests that are part of only one framework (for example, via `--only`)

To specify test arguments per test framework, you can add the arguments in your build definition or
in the test field of the bloop configuration files.

## Test only a specific test case

The mechanism to test only one single case in a suite/specification depends
largely on the test framework. A few examples:

1. JUnit: `bloop test foo -o CubeCalculatorTest -- "*a single test case*"`
2. ScalaTest: `bloop test foo -o CubeCalculatorTest -- -z "a single test case"`
3. utest: `bloop test foo -o CubeCalculatorTest -- "CubeCalculatorTest.a single test case"`

Consult the documentation of your test framework to know how to run an exact
or fuzzy match for a test.

## Pass JVM arguments to test

The configuration file of a project contains the options the tests should be run with.

To add new arguments to a test execution, append `-J` to every argument after `--`. These arguments
will be passed directly to the forked virtual machine. For example, let's increase the heap size of
our tests until 4gb.

```bash
→ bloop test foo -- -J-Xms2g -J-Xmx4g
CubeCalculatorTest:
- CubeCalculator.cube
Execution took 11ms
1 tests, 1 passed
All tests in CubeCalculatorTest passed

===============================================
Total duration: 11ms
All 1 test suites passed.
===============================================
```

To debug our tests, we can use instead
`-J-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005`.


## Run an application

If our application defines one main method, we can run it:

```bash
→ bloop run foo
Hello, World!
```

The `bloop run` command only accepts a project as a command.

## Run a concrete main method

Bloop complains where there is more than one main method defined in your project and you run a
vanilla `bloop run` invocation.

Assuming we duplicate the main entrypoint with a scond `CubeCalculator2`, bloop emits an error:

```bash
→ bloop run foo
[E] Multiple main classes found. Expected a default main class via command-line or in the configuration of project 'foo'
[E] Use the following main classes:
[E]  * CubeCalculator2
[E]  * CubeCalculator
```

To fix the error, specify the main class you want to run with `-m` or `--main`:

```bash
→ bloop run foo -m foo.HelloWorld
Hello, World!
```

## Pass JVM arguments to run

Like [Pass JVM arguments to test](#pass-jvm-arguments-to-test), you can add new arguments to the
execution by appending `-J` to every argument after `--`. These arguments will be passed directly to
the forked virtual machine.

```bash
→ bloop run foo -- -J-Xms2g -J-Xmx4g
Hello, World!
```

To debug an application, pass the following JVM argument instead
`-J-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005`.


## Enable file watching

When using the command-line tool, it's common to run a bloop task with file watching enabled. For
example, you can run your tests in a loop with:

```bash
→ bloop test foo -w
CubeCalculatorTest:
- CubeCalculator.cube
Execution took 18ms
1 tests, 1 passed
All tests in CubeCalculatorTest passed

===============================================
Total duration: 18ms
All 1 test suites passed.
===============================================
Watching 2 directories... (press Ctrl-C to interrupt)
```

`-w` or `--watch` is available for `compile`, `test` and `run`. You can interrupt file watching by
pressing <kbd>Ctrl</kbd> + <kbd>C</kbd>.

> It's not recommended to run `-w` at the same time you use [Bloop via a text editor](ides/overview)
> given that concurrent actions may collide. Such actions will be handled gracefully in future bloop
> releases.

## Run an Ammonite REPL on a project

The `console` commands runs an Ammonite REPL with a project's classpath.

```bash
→ bloop console foo
Compiling foo (1 Scala source)
Compiled foo (290ms)
https://repo1.maven.org/maven2/com/lihaoyi/ammonite_2.12.8/maven-metadata.xml
  No new update since 2019-05-19 04:07:48
Loading...
Compiling (synthetic)/ammonite/predef/interpBridge.sc
Compiling (synthetic)/ammonite/predef/replBridge.sc
Compiling (synthetic)/ammonite/predef/DefaultPredef.sc
Welcome to the Ammonite Repl 1.6.7-1-a44339b
(Scala 2.12.8 Java 1.8.0_202-ea)
If you like Ammonite, please support our development at www.patreon.com/lihaoyi
@  
```

Use `--ammonite-version` to use a particular version of Ammonite. By default,
the latest released version is used.

## Compile, test and run several projects

The `compile`, `test` and `run` actions take several projects as arguments which allows you to
process several projects in one go. For example, compile `foo`, `bar` and `baz` with:

```bash
→ bloop compile foo bar baz
Compiling foo (1 Scala source)
Compiled foo (290ms)
Compiling bar (1 Scala source)
Compiled bar (455ms)
Compiling baz (1 Scala source)
Compiled baz (229ms)
```

## Summary

You've just learned the most basic bloop commands. Learn more commands with `bloop --help`:

```bash
→ bloop --help
bloop 1.3.0-RC1
Usage: bloop [options] [command] [command-options]


Available commands: about, autocomplete, bsp, clean, compile, configure, console, help, link, projects, run, test
Type `bloop 'command' --help` for help on an individual command
     
Type `--nailgun-help` for help on the Nailgun CLI tool.
```

If you're looking for the flags supported by a command such as `compile`, run `bloop compile --help`:

```bash
→ bloop compile --help
Command: compile
Usage: bloop compile <project>
  --project | -p  <value>
        The project to compile (will be inferred from remaining cli args).
  --incremental  
        Compile the project incrementally. By default, true.
  --pipeline  
        Pipeline the compilation of modules in your build. By default, false.
  --reporter  <value>
        Pick reporter to show compilation messages. By default, bloop's used.
  --watch | -w  
        Run the command when projects' source files change. By default, false.
  --config-dir | -c  <.bloop>
        File path to the bloop config directory, defaults to `.bloop` in the current working directory.
  --version | -v  
        If set, print the about section at the beginning of the execution. Defaults to false.
  --verbose  
        If set, print out debugging information to stderr. Defaults to false.
  --no-color  
        If set, do not color output. Defaults to false.
  --debug  <value>
        Debug the execution of a concrete task.
```

For a complete overview, visit the [CLI `--help`](cli-reference) reference page.
