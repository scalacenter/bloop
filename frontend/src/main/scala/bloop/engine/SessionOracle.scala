package bloop.engine

import bloop.io.AbsolutePath

import scala.concurrent.Future

trait SessionOracle {
  def registerAsIdle(session: ClientSession.Bsp): Unit

  def syncOnScheduledTasks(session: ClientSession.Bsp): Unit

  def scheduleTasksForIdleBspSessions(
      buildUri: AbsolutePath,
      spawnTasks: ClientSession.Bsp => List[Future[_]]
  ): Unit
}

object SessionOracle {
  val global: SessionOracle = {
    // Implements a session oracle that only registers BSP sessions as idle
    new SessionOracle {
      import java.util.concurrent.ConcurrentHashMap
      private val idleBspSessions =
        new ConcurrentHashMap[AbsolutePath, List[ClientSession.Bsp]]()
      private val scheduled =
        new ConcurrentHashMap[ClientSession.Bsp, List[Future[_]]]()

      def registerAsIdle(session: ClientSession.Bsp): Unit = {
        idleBspSessions.compute(
          session.buildUri,
          (uri, existingClients) => {
            if (existingClients == null) List(session)
            else session :: existingClients
          }
        )
        ()
      }

      def syncOnScheduledTasks(session: ClientSession.Bsp): Unit = {
        val existingClients = idleBspSessions.get(session.buildUri)
        if (existingClients == null) ()
        else {
          // TODO: Block on background tasks
          ???
        }
      }

      def scheduleTasksForIdleBspSessions(
          buildUri: AbsolutePath,
          spawnTasks: ClientSession.Bsp => List[Future[_]]
      ): Unit = {
        val existingClients = idleBspSessions.get(buildUri)
        if (existingClients == null) ()
        else {
          val bspSessions = existingClients.collect { case s: ClientSession.Bsp => s }
          if (bspSessions.isEmpty) ()
          else {
            scheduled.synchronized {}
            bspSessions.foreach { session =>
              val tasks = spawnTasks(session)
              ???
            }
          }
        }
      }
    }
  }
}
