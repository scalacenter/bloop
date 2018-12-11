---
id: usage
title: Quickstart
sidebar_label: Quickstart
---

## Requirements

1. A build with some projects in it (the guide assumes `foo` and `foo-test`)
1. You have followed the [Installation Guide](/bloop/setup), which means:
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

As our logs tell us, Bloop has compiled both `foo-test` and `foo`. This is expected given that `foo`
is a dependency of `foo-test` and it was not already compiled.

## Test

Once the tests are compiled, we can test them.

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

If you notice, we run `bloop test foo` instead of `bloop test foo-test`, the project that actually
contains the test sources. Bloop assumes that when you run `bloop test foo` you really mean the
latter and therefore treats both the same. It's idiomatic to use the shorter version.

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

`-w` or `--watch` is available for `compile`, `test` and `run`. As the last line tells us, you can
interrupt file watching by pressing <kbd>Ctrl</kbd> + <kbd>C</kbd>.

> It's not recommended for the moment to run `-w` for the moment if you use [Bloop via a text editor](ides/overview).

## Clean the caches

Clean the compilation caches in your build with:

```bash
→ bloop clean foo-test
→
```

This operation is uncommon but might be useful if the incremental compiler state is causing spurious
errors. `clean` does not remove class files, it only cleans the incremental compilation state. If
you want to remove the class files, do it manually.
 
By default, `bloop clean` only cleans the cache of a given project. If you want to clean the cache
of all the dependent projects too, you can run it with `--include-dependencies` or `--propagate`.

```bash
→ bloop clean foo-test --propagate
→
```

Now testing again should trigger the compilation of both `foo` and `foo-test`.

```bash
→ bloop test foo
Compiling foo (1 Scala source)
Compiled foo (290ms)
Compiling foo-test (1 Scala source)
Compiled foo-test (455ms)
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

## Test downstream projects

Just as `bloop clean`, `bloop test` only runs tests on a concrete project. You can run tests for
your project and all your dependencies by using `--propagate` too, just as in the previous `clean`
example.

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

## Pass arguments to test

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
1. Your test project uses only one test framework
1. You have a project with tests of different test frameworks (e.g. Scalacheck and Scalatest) but
you're filtering out tests that are part of only one framework (for example, via `--only`)

## Summary

You've just learned the most basic bloop commands. Learn more commands with `bloop --help`:

```bash
→ bloop --help
bloop @VERSION@
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
