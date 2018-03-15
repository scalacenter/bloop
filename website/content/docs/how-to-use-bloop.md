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

The `bloop` client is the tool that you use to run any bloop command. The
client is a python script that implements Nailgun's protocol and communicates
with the server. It is a fast CLI tool that gives you immediate feedback from
the server.

#### Tab-completion

The client supports tab-completion on ZSH. For now, the installation process is
manual but we plan on automating it. Any work on this area is highly welcome.

To install zsh command completions, copy the [completions][zsh-completions]
file to your existing completions zsh directory, if it exists.

If it doesn't exist, you can create it and update your `.zshrc` script as
follows:

```sh
$ mkdir -p ~/.zsh/completion
$ cp etc/_bloop ~/.zsh/completion/
$ echo 'fpath=(~/.zsh/completion $fpath)' >> ~/.zshrc
$ echo 'autoload -Uz compinit ; compinit' >> ~/.zshrc
```

Now, reload your shell (or close and open it again) to make zsh pick up bloop
completions.

[zsh-completions]: https://raw.githubusercontent.com/scalacenter/bloop/master/etc/_bloop

### `bloop` server

The server is called `blp-server`, and it's a long-running application that
runs on the background and keeps the state of all your projects and compiler
instances.

When you run `bloop compile` with the client, the server receives the request
and spawns a thread to compile your project, logging you back everything that
the compiler outputs. This server accepts requests in parallel except for
those tasks that are already running.
