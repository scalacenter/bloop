+++
description = "Understand how Bloop works and how to integrate it in your workflow"
title = "Understanding Bloop"
date = "2018-02-09T10:15:00+01:00"
draft = false
weight = 2
bref = "Understanding our client - server model, how to take advantage of it, and why we get better performance"
toc = true
+++

<h3 class="section-head" id="bloop-overview"><a href="#bloop-overview">An overview of
Bloop</a></h3>

Bloop is a client - server application that takes care of compiling, testing and running your
project as fast as possible, and without getting in your way.

It is formed out of 3 different components:

<h5>`bloop`, the client</h5>

`bloop` is the client application that you use to start the compilation, run the tests, etc. on your
project. This is a python script that implements a Nailgun client.

<h5>`blp-server`, the server</h5>

`blp-server` is the Bloop server. The server tracks the state of your projects, it keeps your
compilers hot and performs other operations such as running your tests.

<h5>`blp-shell`, the Bloop shell</h5>

The Bloop shell, `blp-shell`, is an interactive shell that we use for benchmarking, and that was
used in early versions of Bloop. It has less features than the Nailgun integration, and is
unsupported. <mark>We advise you against using it.</mark>

<h3 class="section-head" id="bloop-overview"><a href="#bloop-overview">Bloop's philosophy</a></h3>

Bloop wants to help developers get more productive by making their usual edit-compile-test workflow
faster. Bloop doesn't want to replace your build tool, but rather complement it: Bloop provides a
small, focused tool that lets you perform the tasks that you do most more efficiently. When you need
to do more complex tasks, like publishing for instances, you should rely on your usual build tool.

This small tool is easier to use and less resources hungry. Bloop lets you focus on your work, and
takes care of building it.

<h3 class="section-head" id="bloop-productive"><a href="#bloop-productive">How does Bloop help you?</a></h3>

<h5>Bloop is not intrusive</h5>

The Bloop client is not a long running application like the sbt shell. You run it, get the result,
and you have your shell back. This is closer to the workflow that many developers are love and are
accustomed to from Maven and Gradle for instance.

<h5>Bloop keeps your compilers hot</h5>

Java applications are commonly criticised for their slow startup. However, once an application has
become "hot", it runs really fast. Whenever we restart the application, we'll first suffer from bad
performance until the code gets optimized again by the JVM. This is why running `scalac` directly
can be much slower than compiling with sbt.

The bloop server keeps your compilers hot and shares them across project. This means that even a
project that you have never opened will be compiled faster, thanks to the optimizations that took
place while compiling other projects.

<h5>Bloop is simple to use</h5>

Bloop uses a command line interface that is simple to use and doesn't require you to learn a complex
DSL to interact with it. If you need help with something, just ask Bloop for some `--help`.
