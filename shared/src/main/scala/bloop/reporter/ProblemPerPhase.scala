package bloop.reporter

/**
 * A problem that is mapped to the phase where it occurred.
 *
 * @param problem A problem reported by Zinc.
 * @param phase An optional phase in case compiler reports a problem before a phase is registered.
 */
final case class ProblemPerPhase(problem: xsbti.Problem, phase: Option[String])
