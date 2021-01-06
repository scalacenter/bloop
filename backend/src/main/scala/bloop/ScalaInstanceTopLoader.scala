package bloop

/**
 * The parent classloader of the Scala compiler.
 * It was originally copied and pasted from
 * https://github.com/lampepfl/dotty/blob/ea65338e06142f41f0e68b23250c8e22b0ba8837/sbt-dotty/src/dotty/tools/sbtplugin/DottyPlugin.scala#L586-L627
 *
 * To understand why a custom parent classloader is needed for the compiler,
 * let us describe some alternatives that wouldn't work.
 *
 * - `new URLClassLoader(urls)`:
 *   The compiler contains sbt phases that callback to sbt using the `xsbti.*`
 *   interfaces. If `urls` does not contain the sbt interfaces we'll get a
 *   `ClassNotFoundException` in the compiler when we try to use them, if
 *   `urls` does contain the interfaces we'll get a `ClassCastException` or a
 *   `LinkageError` because if the same class is loaded by two different
 *   classloaders, they are considered distinct by the JVM.
 *
 * - `new URLClassLoader(urls, bloopLoader)`:
 *    Because of the JVM delegation model, this means that we will only load
 *    a class from `urls` if it's not present in the parent `bloopLoader`, but
 *    Bloop uses its own version of the scala library which is not the one we
 *    need to run the compiler.
 *
 * Our solution is to implement an URLClassLoader whose parent is
 * `new ScalaInstanceTopLoader(bloopClassLoader, bootClassLoader)`.
 * We override `loadClass` to load the `xsbti.*` interfaces from the
 * `bloopClassLoader` and the JDK classes from the bootClassLoader.
 */
class ScalaInstanceTopLoader(bloopClassLoader: ClassLoader, parent: ClassLoader)
    extends ClassLoader(parent) {
  override protected def loadClass(name: String, resolve: Boolean): Class[_] = {
    if (name.startsWith("xsbti.")) {
      val c = bloopClassLoader.loadClass(name)
      if (resolve) resolveClass(c)
      c
    } else super.loadClass(name, resolve)
  }
}
