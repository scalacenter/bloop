Add the bloop bundle to [scoop](https://github.com/lukesampson/scoop):

```bash
scoop bucket add bloop-stable https://github.com/scalacenter/scoop-bloop.git
```

Check that the bucket is correctly configured:

```bash
scoop bucket list
scoop search bloop
// You should see the bloop scoop package listed here
```

Then, install it with:

```bash
scoop install bloop
```

### Requirements

1. **Python 2 or 3**. The installation script is written in Python.
1. **Java 8**. The incremental compiler does not yet support Java 11.

> You must ensure that Java 8 is installed manually (as well as ensuring that you run bloop with
Java 8). The python dependency is automatically installed by scoop.
