
### Requirements

- **Java 8 or higher**
- Coursier >= `2.0.0-RC6-13`

### Installation options

```sh
coursier install bloop --only-prebuilt=true
```

Coursier will install bloop in its installation directory. When installing, you
might get a log similar to this:

```
➜  ~ ➜  ~ coursier install bloop
https://repo1.maven.org/maven2/io/get-coursier/apps/maven-metadata.xml
  100.0% [##########] 993 B (20.2 KiB / s)
https://repo1.maven.org/maven2/io/get-coursier/apps/maven-metadata.xml
  No new update since 2020-05-11 10:44:44
https://repo1.maven.org/maven2/io/get-coursier/apps/0.0.23/apps-0.0.23.pom
  100.0% [##########] 1.3 KiB (12.9 KiB / s)
https://repo1.maven.org/maven2/org/scala-lang/scala-library/maven-metadata.xml
  No new update since 2020-04-23 06:26:04
Wrote bloop
Warning: /Users/jvicentecantero/Library/Application Support/Coursier/bin is not in your PATH
To fix that, add the following line to ~/.zshrc
```

Make sure you add the `Coursier` binary directory to your `$PATH`.
