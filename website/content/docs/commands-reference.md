+++
description = "An explanation of all the commands that Bloop provides"
title = "Commands reference"
date = "2018-02-09T10:15:00+01:00"
draft = false
weight = 4
bref = "An explanation of all the commands that Bloop provides"
toc = true
+++

## `bloop projects`

This commands displays all the projects that have been loaded from the current working directory:

```
$ bloop projects
Projects loaded from '/Users/martin/foobar/.bloop':
 * foobar
 * foobar-test
```

This command can also be used to generate `dot` graph: <sample>

## `bloop compile`

This command takes care of starting compilation for a project and all of its dependencies.

The supported options are:

<dl>
  <dt><code>-p</code> or <code>--project</code></dt>
  <dd>
    <p>Select the project to compile. This argument is required, but will be inferred if it's a remaining CLI args.</p>
    <p><em>example:</em> <samp>bloop compile foobar</samp></p>
    <p><em>example:</em> <samp>bloop compile -p foobar</samp></p>
  </dd>

  <dt><code>--incremental</code></dt>
  <dd>
    <p>
      Whether to compile the project incrementally. Defaults to <code>true</code>. If this option is
      set to false, then the compiler will recompile all the sources.
    </p>
    <p><em>example:</em> <samp>bloop compile foobar --incremental=false</samp></p>
  </dd>

  <dt><code>--reporter</code></dt>
  <dd>
    <p>
      The error reporter to use. By default, Bloop's error reporter is use. The possible choices
      are <code>bloop</code> and <code>scalac</code>.
    </p>
    <p><em>example:</em> <samp>bloop compile foobar --reporter scalac</samp>
  </dd>

  <dt><code>--watch</code></dt>
  <dd>
    <p>
      Whether to re-run the command when the projects' files are modified. Each time one of the
      file of the project, or of the dependencies of the project, is modified, the
      <code>compile</code> command will be issued again, automatically.
    </p>
    <p><em>example:</em> <samp>bloop compile foobar --watch</samp></p>
  </dd>
</dl>

## `bloop test`

This command runs the tests for one or more projects.

The supported options are:

<dl>
  <dt><code>-p</code> or <code>--project</code></dt>
  <dd>
    <p>Select the project to test. This argument is required, but will be inferred if it's a remaining CLI args.</p>
    <p><em>example:</em> <samp>bloop test foobar</samp></p>
    <p><em>example:</em> <samp>bloop test -p foobar</samp></p>
  </dd>

  <dt><code>--isolated</code></dt>
  <dd>
    <p>
      Whether to run the tests only for the specified project, not including its dependencies. By
      default, the tests are run for the dependencies of the specified project and the project.
    </p>
    <p><em>example:</em> <samp>bloop test foobar --isolated</samp></p>
  </dd>

  <dt><code>--reporter</code></dt>
  <dd>
    <p>
      The error reporter to use. By default, Bloop's error reporter is use. The possible choices
      are <code>bloop</code> and <code>scalac</code>.
    </p>
    <p><em>example:</em> <samp>bloop test foobar --reporter scalac</samp>
  </dd>

  <dt><code>--watch</code></dt>
  <dd>
    <p>
      Whether to re-run the command when the projects' files are modified. Each time one of the
      file of the project, or of the dependencies of the project, is modified, the
      <code>test</code> command will be issued again, automatically.
    </p>
    <p><em>example:</em> <samp>bloop test foobar --watch</samp></p>
  </dd>
</dl>

## `bloop run`

This command is used to run the code of your project. It requires having at least one runnable class
in your project.

The supported options are:

<dl>
  <dt><code>-p</code> or <code>--project</code></dt>
  <dd>
    <p>Select the project to run. This argument is required, but will be inferred if it's a remaining CLI args.</p>
    <p><em>example:</em> <samp>bloop run foobar</samp></p>
    <p><em>example:</em> <samp>bloop run -p foobar</samp></p>
  </dd>

  <dt><code>-m</code> or <code>--main</code></dt>
  <dd>
    <p>
      The fully qualified class name of the class to run. Leave blank to let Bloop select
      automatically the class to run.
    </p>
    <p><em>example:</em> <samp>bloop run foobar --main com.acme.Main</samp></p>
  </dd>

  <dt><code>--reporter</code></dt>
  <dd>
    <p>
      The error reporter to use. By default, Bloop's error reporter is use. The possible choices
      are <code>bloop</code> and <code>scalac</code>.
    </p>
    <p><em>example:</em> <samp>bloop run foobar --reporter scalac</samp>
  </dd>

  <dt><code>--args</code></dt>
  <dd>
    <p>
      The arguments to pass to the <code>main()</code> function of the application. it is possible
      to specify this option more than once to pass several parameters.
    </p>
    <p><em>example:</em> <samp>bloop run foobar --args hello --args world</samp>
  </dd>

  <dt><code>--watch</code></dt>
  <dd>
    <p>
      Whether to re-run the command when the projects' files are modified. Each time one of the
      file of the project, or of the dependencies of the project, is modified, the
      <code>run</code> command will be issued again, automatically.
    </p>
    <p><em>example:</em> <samp>bloop run foobar --watch</samp></p>
  </dd>
</dl>

## `bloop console`

`bloop console` starts a Scala REPL with your project and its dependencies on the classpath.

The following options are supported:

<dl>
  <dt><code>-p</code> or <code>--project</code></dt>
  <dd>
    <p>Select the project for which to start the console. This argument is required, but will be inferred if it's a remaining CLI args.</p>
    <p><em>example:</em> <samp>bloop console foobar</samp></p>
    <p><em>example:</em> <samp>bloop console -p foobar</samp></p>
  </dd>

  <dt><code>--reporter</code></dt>
  <dd>
    <p>
      The error reporter to use. By default, Bloop's error reporter is use. The possible choices
      are <code>bloop</code> and <code>scalac</code>.
    </p>
    <p><em>example:</em> <samp>bloop console foobar --reporter scalac</samp>
  </dd>

  <dt><code>--exclude-root</code></dt>
  <dd>
    <p>
      Use this argument to start the console with all the dependencies of your project on the
      classpath, but not the project itself.
    </p>
    <p><em>example:</em> <samp>bloop console foobar --exclude-root</samp>
  </dd>
</dl>

## `bloop clean`

This command is used to remove the results of the compilation.

The following options are supported:

<dl>
  <dt><code>-p</code> or <code>--project</code></dt>
  <dd>
    <p>Select the project to clean. This argument is required.</p>
    <p><em>example:</em> <samp>bloop clean -p foobar</samp></p>
    <p><em>example:</em> <samp>bloop clean foobar</samp></p>
    <p><em>example:</em> <samp>bloop clean -p foobar foobar-test</samp></p>
    <p><em>example:</em> <samp>bloop clean foobar foobar-test biz</samp></p>
  </dd>

  <dt><code>--isolated</code></dt>
  <dd>
    <p>
      Set this option if only the specified project should be cleaned, excluding its dependencies.
    </p>
    <p><em>example:</em> <samp>bloop clean -p foobar --isolated</samp>
  </dd>
</dl>

## `bloop configure`

This command is used to configure the behavior of the Bloop server.

The following options are supported:

<dl>
  <dt><code>--threads</code> or <code>--parallelism</code></dt>
  <dd>
    <p>
      Configures the maximum number of threads that Bloop should use when doing tasks in
      parallel. The default is the number of processors on the machine.
    </p>
    <p><em>example:</em> <samp>bloop configure --threads 8</samp></p>
  </dd>
</dl>

## `bloop bsp`

This command is used to start the <abbr title="Build Server Protocol">BSP</abbr> server.
You can check a specification of BSP <a href="https://github.com/scalacenter/bsp">here</a>.

The following options are supported:

<dl>
  <dt><code>--p</code> or <code>--protocol</code></dt>
  <dd>
    <p>
      The connection protocol that the server should use. Defaults to <code>local</code>.
    </p>
    <p>
      The accepted values are <code>local</code> and <code>tcp</code>
    </p>
    <p>
      <span class="label warning">Note</span>
      when using the `tcp` protocol, the `--socket` option must be set, too.
    </p>
    <p><em>example:</em> <samp>bloop bsp --protocol tcp</samp></p>
  </dd>

  <dt><code>--h</code> or <code>--host</code></dt>
  <dd>
    <p>
      The hostname of the server.
    </p>
    <p>
      <span class="label warning">Note</span>
      this option can only be set when the `--protocol` is set to `tcp`.
    </p>
    <p><em>example:</em> <samp>bloop bsp --protocol tcp --host my-server.com</p>
  </dd>

  <dt><code>--port</code></dt>
  <dd>
    <p>
      The port on which the server is listening. Defaults to <code>5001</code>
    </p>
    <p>
      <span class="label warning">Note</span>
      this option can only be set when the `--protocol` is set to `tcp`.
    </p>
    <p><em>example:</em> <samp>bloop bsp --protocol tcp --port 65001</p>
  </dd>

  <dt><code>-s</code> or <code>--socket</code></dt>
  <dd>
    <p>
      The path to the UNIX socket file. Ignored if OS is not Unix (Linux, OSX, *bsd).
    </p>
    <p>
      <span class="label warning">Note</span>
      this option can only be set when the `--protocol` is set to `local`. You can specify the pipe name as well if you aim to support Windows too.
    </p>
    <p><em>example:</em> <samp>bloop bsp --socket /var/run/bsp</p>
  </dd>

  <dt><code>--pn</code> or <code>--pipename</code></dt>
  <dd>
    <p>
      The name of the Windows communication pipe. Ignored if OS is not Windows.
    </p>
    <p>
      <span class="label warning">Note</span>
      this option can only be set when the `--protocol` is set to `local`. You can specify the UNIX socket path if you aim to support UNIX systems too.
    </p>
    <p><em>example:</em> <samp>bloop bsp --protocol local --pipename /var/run/bsp</p>
  </dd>
</dl>
