Bloop is a JVM-based build server that applications such as IDEs (e.g.
[Metals](https://scalameta.org/metals)) or build tools (e.g.
[seed](https://github.com/tindzk/seed)) can depend on. Typically, these
applications use the [Bloop Launcher](docs/launcher-reference) to run bloop
in the background of your machine. This means, you might be already using
Bloop without knowing it.

So, **do you need to install Bloop** if these applications are already
depending on and running bloop in the background?

The answer is no. You should only install bloop as a package if you want to:

- Install and use the **Bloop CLI** to send build commands to the bloop
  server from the comfort of your terminal. This includes autocompletions.
  It is always handy to have the CLI installed in your machine!
- Install the bloop server for the first time in your machine because you're
  planning to use it to build it for something or play with it.
- Have complete control over which bloop server version you're using and how
  it runs in the background. For example, because you want to use a
  [Systemd](https://freedesktop.org/wiki/Software/systemd/) configuration to
  run bloop.

If any of the above are true, please proceed with this guide and jump to the
next step. If none is true or you don't know if they are true or false, it is
recommended you do not install Bloop directly in your machine.

The following page explains how to get Bloop installed in your machine and
how you can export your current project build to Bloop.