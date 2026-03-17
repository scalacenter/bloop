### Requirements

- **JDK 17 or higher** (Bloop CLI uses JDK 17 by default; see [Build Server Reference](../../server.md#custom-java-home) for other versions)
- Coursier >= `2.0.0-RC6-13`

### Installation options

Coursier installation instructions can be found at
https://get-coursier.io/docs/cli-installation.

After successfully installing it you can run:

```sh
cs install bloop --only-prebuilt=true
```

Coursier will install bloop in its installation directory. Ensure the Coursier
bin directory (e.g. `~/Library/Application Support/Coursier/bin` on macOS) is
in your `$PATH` so that the `bloop` command is available.
