---
id: compiler-isolation
title: Implementation Guide of Compiler Isolation
sidebar_label: Implementation Guide of Compiler Isolation
---

# Compilation steps

The first step after the compilation of a module has been triggered is to
compute the `CompileBundle`. The `CompileBundle` is a pack of all the inputs
that are relevant for compilation. The key inputs that the bundle contain
are:

1. `output`: `CompileManagedOutput`
1. `classpath`: `List[File]`
1. `javaSources` and `scalaSources`: both `List[File]`
1. And the hashes of both the `classpath` and `javaSources`/`scalaSources`

Based on these inputs and their fingerprints, bloop decides what to do with
the compilation. If any of the inputs has changed, the compilation process is
started and passed to the `Zinc` engine. Otherwise, the compilation is
returned.

At the same time, if the inputs have changed but Bloop has already started
the compilation of a module for another client, the current compilation will
wait on the ongoing one and will replicate any side effects that happened
during its course. These side effects include any compilation event relevant
for the client (bsp notifications, log messages, et cetera) and any generated
compilation product in the file system.

If no compilation is already running for the given compilation inputs, Bloop
triggers the compilation of the module. The new compilation must be isolated
from other future compilations for the same project, so Bloop makes sure that
changes in the inputs (e.g. class files from previous compilations) do not
affect the compilation while it shares them across all concurrent compilation
processes (e.g. to avoid unnecessary copying). Finally, Bloop redirects the
output of the compilation to a unique directory that no other process has
access to.

## Implementing Compiler Isolation

Compiler isolation can be achieved in different ways. Some of these ways are
more intuitive and complicated than others, so before deciding what
implementation we're going to favor we need to define what will be the
decisive criteria to prefer a solution over the others.

### Acceptance Criteria

1. The compilation must yield correct results no matter how many clients or
   in which order they request compilation.
1. The new compilation engine does not slow down compilations when there is
   only one single client, the most common scenario for Bloop's build server.
1. The compilation scheme is simple enough that can be implemented in the
   Bloop codebase without major changes to Zinc.
1. The implementation is compiler-agnostic and doesn't rely on the specifics
   of how a concrete compiler works.
1. The compiler isolation must work for both classes directories or jars, so
   that when Bloop supports straight-to-jar compilation the compiler isolation
   is not compromised.

### In Search For a Solution

One would think that having a shared read-only classes directory with the
results of the previous compilation and a unique read-write classes directory
would suffice to isolate compilation processes. We write new compilation
products to the unique directory and source symbols from the previous
read-only directory, which is shared by all the concurrent compilations.

However, a read-only classes directory is impractical for an incremental
build server as it entails class files cannot be deleted from it. Deleting
class files from the classes directory is necessary for the incremental
compiler to correctly invalidate symbols and prevent spectacular errors. For
example, if you rename in your project `A` to `B`, Zinc invalidates `A` and
needs to delete its associated class file to guarantee that no other symbols
depending on `A` could ever compile successfully. This way, the classpath
lookup for `A` would always fail and result in a compilation error.

Can we design a solution that sticks to the idea of a read-only classes
directory but allows for class files deletion? A solution that, for example,
takes care of moving class files around special directories to simulate
deletion for current and future concurrent clients?

The answer is yes. It is possible. However, such a solution ends up being
terribly complicated. It requires:

1. A shared delete-only classes directory that is present in the classpath of
the compiled module and contains all class files produced by the latest
successful compilation minus the class files that have been deletd by
concurrent compilation processes;
1. A per-process read-write classes directory where the compiler writes new
class files to and that serves as the free arena where the incremental
compiler can make any change to a class file without affecting other
compilation processes;
1. A shared backup classes directory where we store deleted class files in
the shared delete-only classes directory. The files in this file + the class
files in the shared delete-only classes directory constitute the original
class files from the latest successful compilation at any point in time.

As you can imagine, the solution doesn't end there. There are lots of
tricky interactions moving class files back and forth to isolate compilations
completely and handle race conditions appropiately. On the happy side, this
solution maximizes sharing of class files and, thus, minimizes copying class
files and compilation resources (such as semanticdb files) back and forth
from classes directories -- IO work that is taxing in even in Linux systems
which outperforms other OS such as Windows and macOS when it comes to the
performance of file system operations.

Can we have a solution that is both simple and reasonably fast, meeting the
initial acceptance criteria?

What if, instead of reformulating the purposes of classes directories and
trying to make use of the read-only classes directory, we stick to the
previous mutable read-write classes directory and modify the compilation
engine to enable concurrent clients?

Here's how it could work: every time we compile a module, we check if there
are other compilation processes compiling the same module. If there aren't
any, we schedule a task to copy the input classes directory file-per-file to
a new classes directory and, concurrently, trigger the compilation of the
module. As the copying of class files and the compilation happen at the same
time, we must work out a way to avoid copying over files that are produced by
the new compilation, so we set the output compile directory to a new path.
Whenever the incremental compiler needs to make an change in the original
classes directory, such as the deletion of class files, we wait on the
background IO class file copying to finish. Only then, the change is done.

Now, if a new compilation request comes in, it can trigger the same process
but this time using the recently copied classes directory as the source of
the compilation. If a new compilation request comes in before the copying is
done, it will block until it's done. If two compilation requests are
competing for an IO copy to finish, only one will be able to use the
resulting classes directory copy and a new IO copy will be immediately
triggered to unblock the "losing" client. This same strategy scales to N
clients.

At the end of all compilations, Bloop will copy back the class files from the
target directory to the classes directory. This process can also be triggered
in the background while other incremental compiler runs are triggered or
other modules are compiled. The only mandatory requirement is that when the
compilation has finished and returns, all of those copies have finished.

## The Visibility of the Classes Files Paths

Even though the previous solution seems to work, there's another aspect we
must take into account before settling on a solution: who will use the
compilation outputs in the classes directories and how will they be used?

Only clients can answer these questions, Bloop's build server cannot, so we
must assume they will be used in any way possible. There are two problematic
cases that we must handle:

1. Clients modify in any way the compilation outputs (remove class files,
change file metadata, modify contents of files, et cetera)
1. Clients use the compilation outputs to run/test an application

The first case is problematic because the file changes could be executed when
a compilation is undergoing. That would violate the correctness of our
compilation and **not** isolate our compilation.

The second problematic case has to do with the internals of the JVM. When the
JVM runs an application, it associated every loaded class with the class
file/jar entry it loaded it from. If any of the class files used to run an
application changes because a new compilation is triggered by another client
and writes to the same classes directory, that would crash the JVM.

(Also talk about problems in Windows.)

Therefore, it's clear we must protect ourselves from these scenarios by
limiting the visibility of the compilation side effects in Bloop and
preventing client actions from modifying the compilation inputs of our build
server.

To do that, we can introduce the concept of a visible classes directory and a
managed (or internal) classes directory. Whenever the compilation is done,
all modifications by the compiler would go to the internal classes directory.

Then, every incremental compiler run would schedule a task to keep the
internal classes directory and the visible classes directory up-to-date. We
could do this by file watching the target directory and immediately copying
changes to the visibile classes directory in the background. When the
compilation for a module is over, the file watcher would be stopped. Another
approach would be to do a full copy (listing all files at a given point).

The important conclusion of this section is that by keeping two classes
directories per project we can guarantee that clients' and build server's
actions do not misinteract.

## Performance Analysis of Copying Class Files

