<p align="center">
  <img src="website/static/img//logo.svg" alt="logo" width="30%">
</p>

<p align="center">
Bloop gives you <b>fast</b> edit/compile/test workflows for <b>Scala</b>.
</p>

[![GitHub tag](https://img.shields.io/github/tag/scalacenter/bloop.svg)]()
[![Build Status](https://ci.scala-lang.org/api/badges/scalacenter/bloop/status.svg)](https://ci.scala-lang.org/scalacenter/bloop)
[![Join the chat](https://badges.gitter.im/scalacenter/bloop.svg)](https://gitter.im/scalacenter/bloop)

## Goals

To understand the goals of bloop, we strongly encourage you to read [this Scala blog
post][bloop-release-post].

Bloop is a command-line tool for fast edit/compile/test workflows. Its primary
goal is to compile and test your project as fast as possible, offering a snappy
developer experience. Bloop does not aim to replace your stock build tool, but
rather complement it.

*Disclaimer*: Bloop is in beta, that means that you should not expect
everything to make sense and you should be ready to see unexpected behaviours.
We're working hard to quickly improve it, and we encourage you to update master
on a daily basis if you start using the tool.

## Installation

Please refer to [the installation
instructions](https://scalacenter.github.io/bloop/docs/installation/).

## Documentation

Documentation is available [on our website](https://scalacenter.github.io/bloop/docs/).

### Building Bloop locally

To publish bloop locally, you'll need to clone this repository and use sbt:

```sh
$ git clone --recursive https://github.com/scalacenter/bloop.git
$ cd bloop
$ sbt
> install
> frontend/version # copy this version number
$ cd nailgun
$ git rev-parse HEAD # copy this commit SHA
$ cd ..
# paste the version number and SHA obtained above in the following command:
$ bin/install.py --dest $HOME/.bloop --nailgun <nailgun-commit-sha> --version <version>
```

## Tab-completion

Bloop supports tab-completion on ZSH. To install command completion on ZSH,
copy the [completions][zsh-completions] file to an existing completions
directory, if it exists.

If not, you can create it, and update your `.zshrc` script as follows:

```sh
$ mkdir -p ~/.zsh/completion
$ cp etc/_bloop ~/.zsh/completion/
$ echo 'fpath=(~/.zsh/completion $fpath)' >> ~/.zshrc
$ echo 'autoload -Uz compinit ; compinit' >> ~/.zshrc
```

Closing and reopening your shell should make bloop tab-completions available.

[installation-script]: https://raw.githubusercontent.com/scalacenter/bloop/master/bin/install.sh
[zsh-completions]: https://raw.githubusercontent.com/scalacenter/bloop/master/etc/_bloop
[bloop-release-post]: http://www.scala-lang.org/blog/2017/11/30/bloop-release.html
