package bloop.dap

final class Synchronized[A](private var value: A) {
  def transform(f: A => A): Unit =
    synchronized {
      value = f(value)
    }
}
