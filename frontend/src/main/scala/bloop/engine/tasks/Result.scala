package bloop.engine.tasks

/** The result of the execution of a `Task`. */
sealed trait Result[+T] {

  /** Gets the resulting value of this task. */
  def get: T

  /**
   * Transforms this `Result` if successful.
   *
   * @param op The transformation to apply.
   * @return The transformed `Result` if successful, the `Failure` otherwise.
   */
  final def map[U](op: T => U): Result[U] = this match {
    case Success(value) => Success(op(value))
    case fail: Failure => fail
  }

  /**
   * Transforms this `Result` if successful.
   *
   * @param op The transformation to apply.
   * @return The transformed `Result` if successful, the `Failure` otherwise.
   */
  final def flatMap[U](op: T => Result[U]): Result[U] = this match {
    case Success(value) => op(value)
    case fail: Failure => fail
  }

  /**
   * Recovers a `Failure` with `op`, or returns the result if successful.
   *
   * @param op The recovery function
   * @return The result value if successful, the result of recovery otherwise.
   */
  final def recover[U >: T](op: PartialFunction[Throwable, U]): U = this match {
    case Success(value) => value
    case Failure(reason) => op(reason)
  }
}

/**
 * The result of a successful task execution.
 *
 * @param value The resulting value.
 */
final case class Success[T](value: T) extends Result[T] {
  override def get: T = value
}

/**
 * The result of a failed task execution.
 *
 * @param reason The exception that was thrown.
 */
final case class Failure(reason: Throwable) extends Result[Nothing] {
  override def get: Nothing = throw reason
}
