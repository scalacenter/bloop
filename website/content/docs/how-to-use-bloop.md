+++
toc = true
weight = 2
draft = false
title = "Basics"
date = "2018-02-09T10:15:00+01:00"
description = "Learn how to use bloop and get familiar with its components"
bref = "Learn how to use bloop and get familiar with its client-server architecture"
+++

Bloop has two main components, a client and a server. If you're looking for
ways to extend this model, head to the [Integration
guide]({{< ref "integration-guide.md" >}}).

### `bloop` client

The `bloop` client is the user-facing tool to run any bloop command. The
client is a python script that implements Nailgun's protocol and communicates
with the server. It gives you immediate feedback from the server, usually in
the order of milliseconds.

This client is a fork of the python script inherited from the [Nailgun
project][nailgun], which has been modified to be Bloop friendly. If you ever
want to check the nailgun options, type `bloop --nailgun-help`>

### `bloop` server

The server runs on the background every action emitted by the bloop client. It
then redirects the log streams back to the bloop client. The server is
a long-running application, so it keeps the state and compiler instances cached
across all your builds.

Type `bloop server`  to run the server on a terminal. The server is installed
by default in the same directory than the `bloop` client, so `bloop server`
will try to find the location of the server in the same directory where `bloop`
is installed. If this is not the case, type `bloop
---server-location=/foo/my-blp-server` instead.

<span class="label warning">Note</span> There can only be one server running
per user session. If you want to run more than one server (strongly not
recommended unless you know what you're doing), then specify the nailgun port
in both the server and the client.

If you forget to run the server before using the bloop CLI tool, you'll get the
following output:

```
> $ bloop help
Could not connect to server 127.0.0.1:8212

Have you forgotten to start bloop's server? Run it with `bloop server`.
Check our usage instructions in https://scalacenter.github.io/bloop/

To display Nailgun's help, use `--nailgun-help`.
```

The server accepts concurrent clients for different builds. There is an ongoing
effort to support safe concurrent execution of commands for the same build and
even the same commands.

#### Example

Typing `bloop compile` with the client tells the server via the Nailgun
protocol to compile your project in the server, logging back any output
produced by the compiler.

[nailgun]: https://github.com/facebook/nailgun/
