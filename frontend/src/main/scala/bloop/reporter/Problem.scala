package bloop.reporter

/** Describes a problem (error, warning, message, etc.) given to the reporter. */
final case class Problem private (
                                  /** A unique (per compilation run) number for this message. */
                                  id: Int,
                                  /** The severity of this message. */
                                  severity: xsbti.Severity,
                                  /** The actual content of the message */
                                  message: String,
                                  /** Position in the source code where the message was triggered */
                                  position: xsbti.Position,
                                  /** The category of this problem. */
                                  category: String)
    extends xsbti.Problem
