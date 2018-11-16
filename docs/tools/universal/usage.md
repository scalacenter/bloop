The installation script installs the build server and the bloop command-line application (CLI).
The build server **must be started** before the command-line application is used. Start it with:

```bash
$ bloop server
```

<blockquote>
  <p>
    Note that <code>bloop server</code> is a command-line alias for <code>$BLOOP_INSTALLATION_DIR/blp-server</code>.
    If the server is not located in the same directory as the bloop CLI binary, pass in the server
    location to the `--server-location` flag.
  </p>
</blockquote>

Then, verify your installation by running the command-line application:

```
$ bloop about
bloop v1.1.0

Running on Scala v2.12.7 and Zinc v1.1.0
Maintained by the Scala Center (Martin Duhem, Jorge Vicente Cantero)
```

### Running the server in the background

Bloop's build server is a long-running process designed to provide the fastest compilation possible
to users. As such, users are responsible for managing its lifecycle and minimizing the amount of
times it's restarted.

Depending on your setup, there are several mechanisms to start automatically the build server and
manage its lifetime (and they are all installed by default).

<blockquote>
  <p>
    The following section is Unix only. If you're a Windows user, you will need to run the bloop
    server manually in a long-running terminal session.
  </p>
</blockquote>

#### Systemd

To have the Bloop server be automatically managed by systemd, install Bloop's systemd service:

```bash
$ systemctl --user enable $HOME/.bloop/systemd/bloop.service
$ systemctl --user daemon-reload
```

The build server will be started whenever you log in your computer and killed whenever your session
is closed.

<blockquote>
<p>
It is also possible to only kill the build server when the machine is shut down. Please refer to <a
href="https://wiki.archlinux.org/index.php/Systemd/Users">Systemd's documentation about user
services</a> for advanced configuration.
</p>
</blockquote>

Command examples:

1. `journalctl --user-unit bloop`: check the build server logs.
1. `systemctl --user status bloop`: checks the status of the build server.
1. `systemctl --user start bloop`: starts up the bloop server.
1. `systemctl --user stop bloop`: stops the bloop server.
1. `systemctl --user restart bloop`: restarts the build server.

#### Desktop Entry

A desktop entry is a `bloop.desktop` file which your desktop environment recognizes as a launcher if
it supports the [freedesktop.org Desktop Entry
specification](https://specifications.freedesktop.org/desktop-entry-spec/desktop-entry-spec-latest.html).
For example, Ubuntu recognizes and displays desktop entries in the application menu next to an icon.

Install a desktop entry with:

```bash
$ mkdir -p $HOME/.local/share/applications
$ ln -s $HOME/.bloop/xdg/bloop.desktop $HOME/.local/share/applications/
```

If you want to start the server automatically, add the desktop entry to `autostart`:

```bash
$ mkdir -p $HOME/.config/autostart
$ ln -s $HOME/.bloop/xdg/bloop.desktop $HOME/.config/autostart/
```

### Command-Line Completions

Bloop supports command-line completions in bash, zsh and fish. The use of command-line
autocompletions is recommended as it significantly improves the user experience. The installation
of autocompletions via `curl` requires you to configure the completions manually.

> Note that the following instructions assume that the bloop installation directory is the default
`$HOME/.bloop`.

#### Zsh Completions

Add the following to your `~/.zshrc`:

```sh
autoload -U compinit
fpath=($HOME/.bloop/zsh $fpath)
compinit
```

#### Bash Completions

Add the following to your `~/.bash_profile`:

```sh
. $HOME/.bloop/bash/bloop
```

#### Fish Completions

Symlink the fish completions file in the Bloop installation directory to your local fish completions
directory (usually `~/.config/fish/completions`).

```sh
$ ln -s $HOME/.bloop/fish/bloop.fish ~/.config/fish/completions/bloop.fish
```

> Make sure that the target fish completions directory already exists.

Bloop CLI completions will not work if the build server is not running when the shell is reloaded.
Make sure that, before reloading the fish shell, the build server is started.

If you still experience problems, reload the completion script:

```bash
$ source $HOME/.bloop/fish/bloop.fish bloop.fish
```

Or, if you use [Oh My Fish](https://github.com/oh-my-fish/oh-my-fish):

```bash
$ omf reload
```
