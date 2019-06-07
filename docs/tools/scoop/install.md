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

1. **Java 8 or higher**
1. **Python 2 or 3** (installation script and CLI client are written in Python)