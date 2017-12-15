package bloop.testing

import sbt.testing.{Framework, TaskDef}

/**
 * Groups all the tests that were detected with the `ClassLoader` that was used to detect them.
 *
 * @param classLoader The `ClassLoader` used to detect the tests.
 * @param tests       The test tasks that were discovered, grouped by their `Framework`.
 */
final case class DiscoveredTests(classLoader: ClassLoader, tests: Map[Framework, List[TaskDef]])
