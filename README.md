# bloop-core

*bloop-core* is a fork of [Bloop](https://github.com/scalacenter/bloop) stripped up of its benchmark infrastructure and build integrations, and with a *non-twisted build*.

The main changes to these modules from Bloop mainline are mainly:
- dropped support for Java < 17
- Bloop server only accepting connections via a domain socket (whose support is provided by Java 17)
- ignoring SIGINT when asked so via a Java property
- truncating a file on a periodical basis if it grows to big (intended to be used if the server output is redirected to that file)

## Building

bloop-core is built with sbt, just like Bloop mainline.

Compile its main modules with
```text
$ sbt stuff/compile
```
