package bloop.engine

import org.junit.Test
import org.junit.experimental.categories.Category

import bloop.exec.JavaEnv
import bloop.logging.RecordingLogger
import bloop.Project
import guru.nidi.graphviz.parse.Parser

import xsbti.compile.ClasspathOptionsUtil

@Category(Array(classOf[bloop.FastTests]))
class DagSpec {
  private object TestProjects {
    private val logger = new RecordingLogger
    private val classpathOptions = ClasspathOptionsUtil.boot()
    private val dummyInstance = bloop.ScalaInstance("bla", "ble", "bli", Array(), logger)
    private val dummyPath = bloop.io.AbsolutePath("/tmp/non-existing")
    private val javaEnv = JavaEnv.default

    // format: OFF
    def dummyProject(name: String, dependencies: List[String]): Project =
      Project(name, dummyPath, dependencies.toArray, dummyInstance, Array(), classpathOptions,  dummyPath, Array(),
              Array(), Array(), Array(), javaEnv, dummyPath, dummyPath)
    // format: ON

    val a = dummyProject("a", List())
    val b = dummyProject("b", List("a"))
    val c = dummyProject("c", List("a"))
    val d = dummyProject("d", List("c", "a"))
    val e = dummyProject("e", List())
    val f = dummyProject("f", List("d"))
    val complete = List(a, b, c, d, e, f)
    val g = dummyProject("g", List("g"))
    val recursive = List(a, b, c, d, e, f)
    val h = dummyProject("h", List("i"))
    val i = dummyProject("i", List("h"))
  }

  private def checkParent(d: Dag[Project], p: Project) = d match {
    case Parent(p2, _) => assert(p2 == p, s"$p2 is not $p")
    case Leaf(f) => sys.error(s"$p is a leaf!")
  }

  private def checkLeaf(d: Dag[Project], p: Project) = d match {
    case Parent(p, _) => sys.error(s"$p is a parent!")
    case Leaf(f) => assert(f == p, s"$f is not $p")
  }

  @Test def EmptyDAG(): Unit = {
    val dags = Dag.fromMap(Map())
    assert(dags.isEmpty)
  }

  @Test def SimpleDAG(): Unit = {
    import TestProjects.a
    val projectsMap = List(a.name -> a).toMap
    val dags = Dag.fromMap(projectsMap)
    assert(dags.size == 1)
    checkLeaf(dags.head, a)
  }

  @Test def CompleteDAG(): Unit = {
    val projectsMap = TestProjects.complete.map(p => p.name -> p).toMap
    val dags = Dag.fromMap(projectsMap)

    assert(dags.size == 3)
    checkLeaf(dags.head, TestProjects.e)
    checkParent(dags.tail.head, TestProjects.f)
    checkParent(dags.tail.tail.head, TestProjects.b)
  }

  @Test(expected = classOf[Dag.RecursiveCycle])
  def SimpleRecursiveDAG(): Unit = {
    import TestProjects.g
    val projectsMap = Map(g.name -> g)
    Dag.fromMap(projectsMap)
    ()
  }

  @Test(expected = classOf[Dag.RecursiveCycle])
  def CompleteRecursiveDAG(): Unit = {
    import TestProjects.g
    val projectsMap = TestProjects.complete.map(p => p.name -> p).toMap + (g.name -> g)
    Dag.fromMap(projectsMap)
    ()
  }

  @Test(expected = classOf[Dag.RecursiveCycle])
  def LongerRecursiveDAG(): Unit = {
    import TestProjects.{h, i}
    val recursiveProjects = Map(h.name -> h, i.name -> i)
    val projectsMap = TestProjects.complete.map(p => p.name -> p).toMap ++ recursiveProjects
    Dag.fromMap(projectsMap)
    ()
  }

  @Test def DottifyGraph(): Unit = {
    val projectsMap = TestProjects.complete.map(p => p.name -> p).toMap
    val dags = Dag.fromMap(projectsMap)
    val dotContents = Dag.toDotGraph(dags)
    Parser.read(dotContents)
    ()
  }

  @Test def EmptyDfs(): Unit = {
    val dags = Dag.fromMap(Map())
    val dfss = dags.map(dag => Dag.dfs(dag))
    assert(dfss.isEmpty, "DFS for empty dag is empty")
  }

  @Test def SimpleDfs(): Unit = {
    import TestProjects.a
    val projectsMap = List(a.name -> a).toMap
    val dags = Dag.fromMap(projectsMap)
    val dfss = dags.map(dag => Dag.dfs(dag))
    assert(dfss.size == 1)
    assert(dfss.head == List(a), s"DFS for simple dag does not contain $a")
  }

  @Test def CompleteDfs(): Unit = {
    val projectsMap = TestProjects.complete.map(p => p.name -> p).toMap
    val dags = Dag.fromMap(projectsMap)
    val dfss = dags.map(dag => Dag.dfs(dag))
    assert(dfss.size == 3)
    assert(dfss.head == List(TestProjects.e))
    assert(dfss.tail.head == List(TestProjects.f, TestProjects.d, TestProjects.c, TestProjects.a))
    assert(dfss.tail.tail.head == List(TestProjects.b, TestProjects.a))
  }
}
