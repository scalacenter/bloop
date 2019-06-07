
```sh
curl -L https://github.com/scalacenter/bloop/releases/download/v@VERSION@/install.py | python
```

Bloop installs under the standard `$HOME/.bloop` directory.

### Requirements

1. **Java 8 or higher**
1. **Python 2 or 3** (installation script and CLI client are written in Python)

### Installation options

<blockquote>
<p>
Skip this sub-section if you're not interested in customizing the installation.
</p>
</blockquote>

```
usage: install.py [-h] [-d DEST] -v VERSION -n NAILGUN -c COURSIER
                  [--ivy-home IVY_HOME] [--bloop-home BLOOP_HOME]
```

The installation scripts places the bloop installation in `$HOME/.bloop` by default.
If you want to change the location to, say, `/opt/bloop`, then:

1. Use `--dest /opt/bloop`; and,
2. Add `/opt/bloop` to your `$PATH` in all your live terminals.
