The installation script installs the build server and the bloop command-line
application (CLI). The build server **must be started** before the
command-line application is used.

### Running the server in the background

The AUR package `bloop-systemd` installs a Systemd service in *your user configuration*
to start up the Bloop server automatically when you log into your machine. This
service spares you from the cumbersome process of starting the build server
before using any build client, enable it with:

```
systemctl --user enable bloop
```

Command examples to manager the server:

1. `systemctl --user start bloop`: starts up the bloop server.
1. `systemctl --user stop bloop`: stops the bloop server.
1. `systemctl --user restart bloop`: restarts the bloop server, required after every upgrade.

To learn more about `bloop server` and managing the server lifecycle
automatically, head to the [Build Server Reference](docs/server-reference).

### Command-line completions

The installation script automatically installs completions for bash, zsh and
fish; reload your shell to get it working.
