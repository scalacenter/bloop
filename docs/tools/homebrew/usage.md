The installation script installs the build server and the bloop command-line application (CLI).
The build server **must be started** before the command-line application is used.

### Running the server in the background

The Homebrew formula installs a Mac OS property list (`plist`) to start up the Bloop server
automatically when you log into your machine. The property list spares you from the cumbersome
process of starting the build server before running any bloop CLI command.

Command examples:

1. `cat /usr/local/var/log/bloop/bloop.out.log`: check the build server logs via stdout.
1. `cat /usr/local/var/log/bloop/bloop.err.log`: check the build server logs via stderr.
1. `brew services start bloop`: starts up the bloop server.
1. `brew services stop bloop`: stops the bloop server.
1. `brew services restart bloop`: restarts the bloop server.

### Command-line completions

The installation script will automatically install completions for bash, zsh and fish.
