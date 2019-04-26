---
id: version-1.2.0-server-reference
title: Build Server Reference
sidebar_label: Build Server
original_id: server-reference
---

The build server is at the core of bloop. It runs in the background and is
the responsible for scheduling and running client actions.

Bloop's build server is a long-running process designed to provide the fastest
compilation possible to users. As such, users are responsible for managing its
lifecycle and minimizing the amount of times it's restarted.

## Start the build server

At the end of the day, the build server is an artifact in Maven Central. However,
the recommended way of starting the server is via `bloop server`.

`bloop server` is an OS-independent way of starting the server that abstracts over
some of the messy details of running a JVM application with the right configuration.
For example, `bloop server`:

1. Finds the location of a bootstrapped jar automatically in the bloop installation
   directory
2. Runs the server with the jvm options in the `.jvmopts` file in the bloop installation
   directory. The `.jvmopts` file can contain the flags separated by either a new line or
   a whitespace.
3. Provides a way to evolve the way the server is run and managed in the future, which
   makes it especially compatibility-friendly.

The bloop installation directory is the directory where `bloop` is located. In Unix-based
systems, the bloop installation directory can be found by running `which bloop`.

<blockquote class="grab-attention">
If you are integrating your tool with bloop and want to install and start the server
<i>automatically</i> in the background, you can use Bloop's built-in <a href="launcher-reference">Launcher</a>.
</blockquote>

### `bloop server`

#### Usage

**Synopsis**: `bloop [FLAGS...] server [JVM_OPTS...] NAILGUN_PORT`

* `NAILGUN_PORT` must be a free TCP port. For now, only ports in the localhost is
  supported.
* `JVM_OPTS` must be valid JVM arguments prefixed with `-J`, used to pass in
  temporary jvm options to the server. For a permanent solution, add the options in
  `.jvmopts` file.

#### Flags

<dl>
<dt><code>--server-location</code> (type: <code>path</code>)</dt>
<dd><p>Use the server jar or script in the given path</p></dd>
</dl>

## Automatic management of the server

It is generally a good practice to have a way to manage the lifecycle of the server.
Depending on your operating system, there exist several solutions that allow you to
start, stop, restart and inspect the status of the build server at any time.

Bloop supports the following mechanisms out-of-the-box:

1. `brew services` in OSX systems
2. `systemd` in Linux systems
3. Desktop Entries in systems that follow the [XDG Desktop Entry Specification](https://standards.freedesktop.org/desktop-entry-spec/latest/)

> Windows users do not have a way of starting the server via Windows Services, so the
> lifecycle management has to be manual. Do you want to help improve the situation?
> Check this [ticket](https://github.com/scalacenter/bloop/issues/766).

### via `brew services`

Brew services are powered by `launchd` and need a macOS property list (`plist`) that explains how to
start the Bloop server and under which conditions. The property list is installed by default in the
[Homebrew](../setup#homebrew) installation and doesn't require any extra steps to use it.

Command examples:

1. `cat /usr/local/Cellar/bloop/$version/log/bloop/bloop.out.log`: check the build server logs via stdout.
2. `cat /usr/local/Cellar/bloop/$version/log/bloop/bloop.err.log`: check the build server logs via stderr.
3. `brew services start bloop`: starts up the bloop server.
4. `brew services stop bloop`: stops the bloop server.
5. `brew services restart bloop`: restarts the bloop server.

### via `systemd`

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
2. `systemctl --user status bloop`: checks the status of the build server.
3. `systemctl --user start bloop`: starts up the bloop server.
4. `systemctl --user stop bloop`: stops the bloop server.
5. `systemctl --user restart bloop`: restarts the build server.

### via Desktop Entries

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

