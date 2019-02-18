The installation script installs the build server and the bloop command-line application (CLI).
The build server **must be started** before the command-line application is used. Start it with:

```bash
bloop server
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
ln -s $HOME/.bloop/fish/bloop.fish ~/.config/fish/completions/bloop.fish
```

> Make sure that the target fish completions directory already exists.

Bloop CLI completions will not work if the build server is not running when the shell is reloaded.
Make sure that, before reloading the fish shell, the build server is started.

If you still experience problems, reload the completion script:

```bash
source $HOME/.bloop/fish/bloop.fish bloop.fish
```

Or, if you use [Oh My Fish](https://github.com/oh-my-fish/oh-my-fish):

```bash
omf reload
```
