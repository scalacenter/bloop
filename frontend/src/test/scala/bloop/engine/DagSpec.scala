package bloop.engine

import org.junit.Test
import bloop.Project
import guru.nidi.graphviz.parse.Parser

class DagSpec {
  private object TestProjects {
    import xsbti.compile.{PreviousResult, CompileAnalysis, MiniSetup}
    import java.util.Optional
    private val dummyInstance = bloop.ScalaInstance("bla", "ble", "bli", Array())
    private val dummyPath = bloop.io.AbsolutePath("/tmp/non-existing")

    // format: OFF
    def dummyProject(name: String, dependencies: List[String]): Project =
      Project(name, dummyPath, dependencies.toArray, dummyInstance, Array(), dummyPath, Array(),
              Array(), Array(), Array(), dummyPath, dummyPath)
    // format: ON

    val a = dummyProject("a", List())
    val b = dummyProject("b", List("a"))
    val c = dummyProject("c", List("a"))
    val d = dummyProject("d", List("c", "a"))
    val e = dummyProject("e", List())
    val f = dummyProject("f", List("d"))
    val complete = List(a, b, c, d, e, f)
    val g = dummyProject("g", List("g"))
    val recurisve = List(a, b, c, d, e, f)
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

    println(Dag.toDotGraph(dags))
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
}
