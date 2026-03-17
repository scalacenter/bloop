The installation script installs the build server and the bloop command-line application (CLI).
The build server **will be started** first time the command-line application is used.

Verify your installation by running the command-line application:

```
bloop v2.0.19

Using Scala v2.12.21 and Zinc v1.12.0
Running on Java JDK v25.0.1 (/Users/tgodzik/.sdkman/candidates/java/25.0.1-tem)
  -> Supports debugging user code, Java Debug Interface (JDI) is available.
Maintained by the Scala Center and the community.
```

### Command-line completions

Bloop does not support PowerShell completions at the moment. However, it does support Bash/Zsh
command-line completions which are available alongside the release artifacts in the Bloop repository.
There are several projects that allow you to use Bash style completions in your PowerShell:

1. [PSReadLine](https://github.com/lzybkr/PSReadLine), a plugin that improves the PowerShell experience and supports bash completions.
1. [`ps-bash-completions`](https://github.com/tillig/ps-bash-completions), a single PowerShell module to install bash completions.
