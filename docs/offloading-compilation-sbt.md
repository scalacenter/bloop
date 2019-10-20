# Offloading compilation from sbt

Offloading compilation from sbt to Bloop requires synchronizing which compile
requests are sent to the Bloop server. The goal of the implementation is to
minimize the amount of these requests and maximize the number of build
targets that are compiled in every request. Guaranteeing such property
provides us with a more efficient implementation and enables the most
efficient use of build pipelining, regardless of the users' build
peculiarities.

When a task is run by sbt, that task may trigger the execution of any other
task in the project. For example, when sbt runs `fullClasspath` on a project,
all the tasks that `fullClasspath` depends on are found and executed in
parallel, even if they have inter dependencies. The sbt engine will run first
those tasks that are dependency-free and will make progress as the tasks'
execution succeeds and more tasks are ready for execution.

This dynamic behavior complicates an efficient integration with Bloop. From
the client side, we'd like to make as few `buildTarget/compile` requests to
the Bloop server as possible so that all of them cover all the compilations.
At the same time, we'd like to enable the task engine to continue discovering
and running tasks as the compilation tasks are completed.

For example, say we have a build with targets `A`, `B` and `C` where `C`
depends on `B` and `B` depends on `A`. The user runs `c/test`. Sbt runs
`c/test` which request the result of `c/test:fullClasspath` before proceeding.
The `c/test:fullClasspath` task runs and does a join on `products` of all
of the classpath dependencies, in this case `B` and `A`, which are
evaluated concurrently in the scheduling pool.

We can see how this behavior runs against our expectations. We would like
`c/test:fullClasspath` to depend on `c/test:compile` only and then collect
the classpath and populate it with the cached analysis. We could change the
definition of `fullClasspath` to do that, yes, but eventually there would be
some tasks we don't have control over (maybe written by users, maybe coming
from third-party sbt plugins) that will bork our compile implementation.
`fullClasspath` is merely giving us a good example of when that could happen.

On top of that, we have the additional constraint that `fullClasspath` or
`dependencyClasspath` can be triggered by the execution of our compile
implementation. These hardcore dynamic dependencies are common in real-world
scenarios, for example one can find them in targets that define JMH
benchmarks or integration tests that require the classpath of an application
in order to test it. These dependencies are legit and inherent to the build
structure so our compile implementation must respect and enforce them.

So, how do we implement compilation offloading then?

The prototype proposed in this pull request makes two important changes to
enable an efficient implementation of compile offloading:

1. Gives up on trying to add synchronization primitives in the sbt task
   engine and instead relies on Bloop to handle concurrent compile requests for
   multiple nodes in the build graph.


## Study of use cases

### Scenario 1

1. Server receives compile request for `A` before `B`.
    1. Server starts to compile `A`.
    1. Server starts to compile `B`. When `CompileGraph` attempts to run
       `setup` on the `Dag` mapped to `A`, it finds that there is an ongoing
       compilation of type `Task[Dag[PartialCompileResult]]` for that node
       that is keyed by the same client *and* origin id. This request blocks
       on that to finish. When it does it proceeds compilation.

Client connects to server
Client sends N compile requests, these compile requests share the same store, which is indexed by 