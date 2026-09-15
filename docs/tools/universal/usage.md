The installation script installs the build server and the bloop command-line application (CLI).
The build server **will be started** first time the command-line application is used.

Verify your installation by running the command-line application:

```
$ bloop about
bloop v@VERSION@

Using Scala v2.12.21 and Zinc v1.12.0
Running on Java JDK v25.0.1 (/Users/tgodzik/.sdkman/candidates/java/25.0.1-tem)
  -> Supports debugging user code, Java Debug Interface (JDI) is available.
Maintained by the Scala Center and the community.
```

### Command-Line Completions

Bloop supports command-line completions in bash, zsh and fish. The use of command-line
autocompletions is recommended as it significantly improves the user experience.

Installing Bloop with coursier does not install the completion scripts, so download the one for
your shell from the release artifacts and put it where your shell looks for completions.

#### Zsh Completions

```sh
mkdir -p ~/.zsh/completion
curl -fL https://github.com/scalacenter/bloop/releases/download/v@VERSION@/zsh-completions \
  -o ~/.zsh/completion/_bloop
```

The file must be named `_bloop`, which is the name zsh looks for. Then add the following to your
`~/.zshrc`:

```sh
fpath=(~/.zsh/completion $fpath)
autoload -U compinit
compinit
```

#### Bash Completions

```sh
mkdir -p ~/.local/share/bloop
curl -fL https://github.com/scalacenter/bloop/releases/download/v@VERSION@/bash-completions \
  -o ~/.local/share/bloop/bloop-completions.bash
```

Then add the following to your `~/.bash_profile`:

```sh
. ~/.local/share/bloop/bloop-completions.bash
```

If you use [bash-completion](https://github.com/scop/bash-completion) 2.x, you can instead save the
file as `~/.local/share/bash-completion/completions/bloop` and skip the `~/.bash_profile` line.

#### Fish Completions

```sh
mkdir -p ~/.config/fish/completions
curl -fL https://github.com/scalacenter/bloop/releases/download/v@VERSION@/fish-completions \
  -o ~/.config/fish/completions/bloop.fish
```

Fish loads completions from that directory automatically, so there is nothing to configure.

Reload your shell to pick up the completions. The first completion may pause while the build
server starts.
