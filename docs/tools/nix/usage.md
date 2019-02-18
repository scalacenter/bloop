The installation script installs the build server and the bloop command-line application (CLI).
The build server **must be started** before the command-line application is used. Start it with:

```bash
blp-server
```

Then, verify your installation by running the command-line application:

```
$ bloop about
bloop v@VERSION@

Running on Scala v2.12.7 and Zinc v1.1.0
Maintained by the Scala Center (Martin Duhem, Jorge Vicente Cantero)
```

### Running the server in the background

Bloop's build server is a long-running process designed to provide the fastest compilation possible
to users. As such, users are responsible for managing its lifecycle and minimizing the amount of
times it's restarted.

We have seen how to manually start the server with `bloop server`. However, you may prefer an
automatic solution that starts the server in the background when you log in and allows you to
quickly restart it. There are several mechanisms to do so, read the [Build Server
Reference](docs/server-reference).

### Command-line completions

The installation script will automatically install completions for bash, zsh and fish.
