---
id: debugging-reference
title: Debugging Reference
sidebar_label: Debugging Reference
---

Bloop allows [BSP][bsp] build clients to start a [DAP][dap] (Debugging
Adapter Protocol) session to run a project or its tests. The core of the
debugging implementation in Bloop relies on Microsoft's [java-debug][]
library and it's exposed through [DAP][dap] support in [BSP][bsp].

Debugging only works on JVMs that support [JDI][jdi] (Java Debug Interface). JDI
is typically available in all JDKs. If you use a JRE, you cannot use
debugging. Run `bloop about` to know if the Java runtime you run bloop with
supports JDI.

Support for debugging is not yet fully worked out and it's gradually becoming
better with every release. We're actively looking for contributions in this
space so drop by our [Gitter channel][gitter] to help out make it better.

## Debugging via an editor

If you want to debug your code with Bloop via *an editor* such as VS Code,
vim, Sublime or emacs you can use [Metals][metals].

If you use *IntelliJ*, use the built-in debugger. This debugger is great for
debugging applications but less ideal to debug tests because the test
execution does not come from Bloop. There can be discrepancies between
Bloop's and IntelliJ's test runner but debugging tests generally works well.

## Debugging from a build client

A client starts a DAP session in the server by sending a `debugSession/start`
BSP request. It is the responsibility of the client to stop the session that
this request starts.

##### Request:

* method: `debugSession/start`
* params: `DebugSessionParams`

```scala
trait DebugSessionParams {
  /** A sequence of build targets affected by the debugging action. */
  def targets: List[BuildTargetIdentifier]

  /** The kind of data to expect in the `data` field. Mandatory. */
  def dataKind: Option[String]

  /** A language-agnostic JSON object interpreted by the server. Mandatory. */
  def data: Option[Json]
}
```

The data field contains language-agnostic information about what action
should the server debug. Bloop supports only two data kinds:

1. `scala-main-class` when `data` contains [`ScalaRunParams`]( https://github.com/scalacenter/bsp/blob/master/docs/bsp.md#scala-run-params).
1. `scala-test-suites` when `data` contains [`ScalaTestParams`]( https://github.com/scalacenter/bsp/blob/master/docs/bsp.md#scala-test-params).

The `targets` field tells the server which build targets should be run or
tested with debugging enabled.

#### Concrete example

If the build client passes a `ScalaRunParams` in the `data` object and its
corresponding data kind to the server, the server will run a target with
those parameters. Likewise for `ScalaTestParams`.

These build actions will produce notifications to the BSP client according to
the [Build Server Protocol specification][bsp]. For example, the execution of
a test suite will produce `build/taskStart` and `build/taskFinish`
notifications containing a `TestReport`.

##### Response:

When the server has run the build action and finished debugging, it responds to the client:

* result: `DebugSessionConnection`, defined as follows
* error: JSON-RPC code and message set in case JDI is not available or an exception happens during the request.

```scala
trait DebugSessionConnection {
  def uri: String
}
```

- The `uri` field is the address where the server is listening to DAP clients.

Build clients can use this `uri` to:

1. Talk to the server via the DAP protocol if they implement the `DAP` client.
2. Pass the `uri` to a tool that talks `DAP`, such as VS Code.
3. Connect to the `uri` but proxy every DAP communication from the BSP server
   to a real `DAP` client and viceversa.

## Limitations

Here are the current limitations with the DAP implementation and debugging support in Bloop.

* BSP responses for `buildTarget/test` and `buildTarget/run` are currently not
  sent to the client. This is a temporary limitation that will be lifted soon.
* There is currently no support for [multi-step `launch` and `attach`
  requests](https://microsoft.github.io/debug-adapter-protocol/specification#Requests_Attach).
  Bloop will ignore the `noDebug` field in a `launch` request. A launch runs
  an application with a debugger attached by default.
* Bloop will use the JVM options from the build target when starting a
  debugger. There is currently no way to allow the users to programmatically change them.
* Bloop does not implement some DAP requests to obtain completions or
  information about sources. If you want to support these, you can have your
  build client run a proxy DAP server that lets Bloop take care of the
  heavy-lifting and that only responds to these client requests.

[dap]: https://microsoft.github.io/debug-adapter-protocol/
[bsp]: https://github.com/scalacenter/bsp/blob/master/docs/bsp.md
[java-debug]: https://github.com/microsoft/java-debug/
[metals]: https://scalameta.org/metals/
[gitter]: https://gitter.im/scalacenter/bloop
[jdi]: https://docs.oracle.com/javase/7/docs/jdk/api/jpda/jdi/