The installation script installs the build server and the bloop command-line application (CLI).
The build server **must be started** before the command-line application is used. Start it with:

```bash
$ bloop server
```

This process can take a while to run the first time is executed. When the nailgun logs show up,
verify your installation by running the command-line application:

```
$ bloop about
bloop v1.1.1

Running on Scala v2.12.7 and Zinc v1.1.1
Maintained by the Scala Center (Martin Duhem, Jorge Vicente Cantero)
```

### Running the server in the background

Bloop's build server is a long-running process designed to provide the fastest compilation possible
to users. As such, users are responsible for managing its lifecycle and minimizing the amount of
times it's restarted.

Bloop does not have a service file for Windows so you have to start and stop the bloop server
manually. It is recommended to have the bloop server running in a long-lived terminal session.

> If you want to improve this situation, check this
[ticket](https://github.com/scalacenter/bloop/issues/766) which proposes a solution to this problem
that is compatible with Windows Services.

To learn more about `bloop server` and managing the server lifecycle automatically, head to the
[Build Server Reference](docs/server-reference).

### Command-line completions

Bloop does not support Powershell completions at the moment. However, it does support Bash/Zsh
command-line completions which are installed by default in the bloop installation directory (under
`/etc`).

There are several projects that allow you to use Bash style completions in your Powershell:

1. [PSReadLine](https://github.com/lzybkr/PSReadLine), a plugin that improves the Powershell experience and supports bash completions.
1. [`ps-bash-completions`](https://github.com/tillig/ps-bash-completions), a single Powershell module to install bash completions.
