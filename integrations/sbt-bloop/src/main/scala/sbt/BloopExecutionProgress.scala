package sbt

final class BloopExecutionProgress extends ExecuteProgress[Task] {
  override type S = Unit
  override def initial: S = ()

  override def registered(
      state: S,
      task: Task[_],
      allDeps: Iterable[Task[_]],
      pendingDeps: Iterable[Task[_]]
  ): S =  {
    task.info.get(Keys.taskDefinitionKey) match {
      case Some(scope) =>
        println(scope.key)
      case None =>
    }
    //println(s"Task ${task.info} depends on ${allDeps.toList.map(_.info)}")
  }

  override def ready(
      state: S,
      task: Task[_]
  ): S = {
    BloopExecutionProgress.t = task

    ()
  }

  override def workStarting(task: Task[_]): Unit = ()
  override def workFinished[T](task: Task[T], result: Either[Task[T], Result[T]]): Unit = ()
  override def completed[T](state: S, task: Task[T], result: Result[T]): S = ()
  override def allCompleted(state: S, results: RMap[Task, Result]): S = ()
}

object BloopExecutionProgress {
  @volatile var t: Task[_] = null
  val install: sbt.Def.Setting[_] = Keys.executeProgress := (_ =>
    new Keys.TaskProgress(new BloopExecutionProgress))
}
