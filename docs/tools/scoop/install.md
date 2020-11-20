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

##### Troubleshooting

If you encounter `coursier.error.ResolutionError$CantDownloadModule: Error downloading` when installing.  

Edit `~\scoop\buckets\bloop-stable\bloop.json` to read:  
```
{
  "version": "1.4.5",
  "url": "https://github.com/scalacenter/bloop/releases/download/v1.4.5/bloop-coursier.json",
  "hash": "sha256:5eba68b553cc028f1aaf4bf58b444f8e0378c4c32ebf2e34cb6e5961f0f66a28",
  "depends": "coursier",
  "bin": "bloop.bat",
  "env_add_path": "$dir",
  "env_set": {
    "BLOOP_HOME": "$dir"
  },
  "installer": {
    "script": "coursier install --install-dir $dir bloop"
  }
}
```

Then, remove the remnants of the failed install with:
`scoop uninstall bloop`

And re-install with:
`scoop install bloop`

