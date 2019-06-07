Bloop is available on the unstable channel of nixos. Install it via:

```bash
nix-channel --add https://nixos.org/channels/nixos-unstable nixos-unstable
nix-channel --update
nix-env -iA nixos-unstable.bloop
```

The service does not start automatically. You can start it with:

```bash
systemctl --user start bloop
```

<blockquote>
  <p>
    Nixos is not yet officially supported and therefore may not be up-to-date with the latest version.
    Bloop v1.1.1 will incorporate an official way to install bloop.
  </p>
</blockquote>

### Requirements

1. **Java 8 or higher**
1. **Python 2 or 3** (installation script and CLI client are written in Python)