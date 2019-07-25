package bloop

import bloop.config.Config
import bloop.data.Project
import bloop.engine.Dag.{DagResult, RecursiveTrace}
import bloop.engine.{Aggregate, Dag, Leaf, Parent}
import bloop.logging.RecordingLogger
import bloop.util.TestUtil
import guru.nidi.graphviz.parse.Parser
import org.junit.experimental.categories.Category
import org.junit.{Assert, Test}

@Category(Array(classOf[bloop.FastTests]))
class DagSpec {

  private val logger = new RecordingLogger
  private val compileOptions = Config.CompileSetup.empty
  private val dummyInstance = TestUtil.scalaInstance
  private val dummyPath = bloop.io.AbsolutePath.completelyUnsafe("")

  // format: OFF
  def dummyOrigin = TestUtil.syntheticOriginFor(dummyPath)
  def dummyProject(name: String, dependencies: List[String]): Project =
    Project(name, dummyPath, None, dependencies, Some(dummyInstance), Nil, Nil, compileOptions,
      dummyPath, Nil, Nil, Nil, Nil, Config.TestOptions.empty, dummyPath, dummyPath,
      Project.defaultPlatform(logger), None, None, dummyOrigin)
  // format: ON

  private object TestProjects {
    val a = dummyProject("a", List())
    val b = dummyProject("b", List("a"))
    val c = dummyProject("c", List("a"))
    val d = dummyProject("d", List("c", "a"))
    val e = dummyProject("e", List())
    val f = dummyProject("f", List("d"))
    val fWithError = dummyProject("f", List("z"))
    val complete = List(a, b, c, d, e, f)
    val completeWithFailedDependency = List(a, b, c, d, e, fWithError)
    val g = dummyProject("g", List("g"))
    val recursive = List(a, b, c, d, e, f)
    val h = dummyProject("h", List("i"))
    val i = dummyProject("i", List("h"))
  }

  def fromMap(projectsMap: Map[String, Project]): List[Dag[Project]] = Dag.fromMap(projectsMap).dags

  private def checkParent(d: Dag[Project], p: Project) = d match {
    case Parent(p2, _) => assert(p2 == p, s"$p2 is not $p")
    case Leaf(f) => sys.error(s"$p is a leaf!")
    case Aggregate(_) => sys.error(s"$p is an aggregate")
  }

  private def checkDependencies(d: Dag[Project], ps: Seq[Project]) = d match {
    case Parent(_, deps) => assert(deps == ps, s"$deps != $ps")
    case Leaf(f) => sys.error(s"$d is a leaf!")
    case Aggregate(_) => sys.error(s"$d is an aggregate")
  }

  private def checkLeaf(d: Dag[Project], p: Project) = d match {
    case Parent(p, _) => sys.error(s"$p is a parent!")
    case Aggregate(dags) => sys.error(s"$p is an aggregate")
    case Leaf(f) => assert(f == p, s"$f is not $p")
  }

  @Test def EmptyDAG(): Unit = {
    val dags = fromMap(Map())
    assert(dags.isEmpty)
  }

  @Test def SimpleDAG(): Unit = {
    import TestProjects.a
    val projectsMap = List(a.name -> a).toMap
    val dags = fromMap(projectsMap)
    assert(dags.size == 1)
    checkLeaf(dags.head, a)
  }

  @Test def CompleteDAG(): Unit = {
    val projectsMap = TestProjects.complete.map(p => p.name -> p).toMap
    val dags = fromMap(projectsMap)

    assert(dags.size == 3)
    checkLeaf(dags.head, TestProjects.e)
    checkParent(dags.tail.head, TestProjects.f)
    checkParent(dags.tail.tail.head, TestProjects.b)
  }

  @Test def CompleteDAGWithMissingDependencies(): Unit = {
    val projectsMap = TestProjects.completeWithFailedDependency.map(p => p.name -> p).toMap
    val DagResult(dags, missingDeps, traces) = Dag.fromMap(projectsMap)

    checkLeaf(dags.head, TestProjects.e)
    // Check that f has no registered dependency
    dags.tail.head match {
      case Parent(p, deps) =>
        assert(p.name == "f")
        assert(deps == Nil, s"$deps != Nil")
      case a => sys.error(s"$a is not a parent node!")
    }

    checkParent(dags.tail.tail.head, TestProjects.b)
    checkParent(dags.tail.tail.tail.head, TestProjects.d)
  }

  @Test
  def SimpleRecursiveDAG(): Unit = {
    import TestProjects.g
    val projectsMap = Map(g.name -> g)
    val result = Dag.fromMap(projectsMap)
    Assert.assertTrue(result.traces == List(RecursiveTrace(List(g, g))))
    ()
  }

  @Test
  def CompleteRecursiveDAG(): Unit = {
    import TestProjects.g
    val projectsMap = TestProjects.complete.map(p => p.name -> p).toMap + (g.name -> g)
    val result = Dag.fromMap(projectsMap)
    Assert.assertTrue(result.traces == List(RecursiveTrace(List(g, g))))
    ()
  }

  @Test
  def LongerRecursiveDAG(): Unit = {
    import TestProjects.{h, i}
    val recursiveProjects = Map(h.name -> h, i.name -> i)
    val projectsMap = TestProjects.complete.map(p => p.name -> p).toMap ++ recursiveProjects
    val result = Dag.fromMap(projectsMap)
    Assert.assertTrue(result.traces == List(RecursiveTrace(List(i, h, i))))
    ()
  }

  @Test def DottifyGraph(): Unit = {
    val projectsMap = TestProjects.complete.map(p => p.name -> p).toMap
    val dags = fromMap(projectsMap)
    val dotContents = Dag.toDotGraph(dags)
    Parser.read(dotContents)
    ()
  }

  @Test def EmptyDfs(): Unit = {
    val dags = fromMap(Map())
    val dfss = dags.map(dag => Dag.dfs(dag))
    assert(dfss.isEmpty, "DFS for empty dag is empty")
  }

  @Test def SimpleDfs(): Unit = {
    import TestProjects.a
    val projectsMap = List(a.name -> a).toMap
    val dags = fromMap(projectsMap)
    val dfss = dags.map(dag => Dag.dfs(dag))
    assert(dfss.size == 1)
    assert(dfss.head == List(a), s"DFS for simple dag does not contain $a")
  }

  @Test def CompleteDfs(): Unit = {
    val projectsMap = TestProjects.complete.map(p => p.name -> p).toMap
    val dags = fromMap(projectsMap)
    val dfss = dags.map(dag => Dag.dfs(dag))
    assert(dfss.size == 3)
    assert(dfss.head == List(TestProjects.e))
    assert(dfss.tail.head == List(TestProjects.f, TestProjects.d, TestProjects.c, TestProjects.a))
    assert(dfss.tail.tail.head == List(TestProjects.b, TestProjects.a))
  }

  @Test def TestUniqueDagFromMap(): Unit = {
    val f = dummyProject("f", List())
    val g = dummyProject("g", List("f"))
    val h = dummyProject("h", List("f"))
    val i = dummyProject("i", List("h"))
    val dags = fromMap(List(f, g, h, i).map(p => p.name -> p).toMap)
    Assert.assertTrue("fromMap returns only one dag", dags.size == 2)
  }

  private object ComplexDag {
    /*
     *         E       B        I
     *         |      /         |
     *         D     /          H   G
     *        /|    /            \ /
     *       / |   /              F
     *      C  |  /
     *       \ | /
     *        \|/
     *         A
     *
     * Note that dependencies flow from top to bottom.
     */
    val a = dummyProject("a", List())
    val b = dummyProject("b", List("a"))
    val c = dummyProject("c", List("a"))
    val d = dummyProject("d", List("c", "a"))
    val e = dummyProject("e", List("d"))
    val f = dummyProject("f", List())
    val g = dummyProject("g", List("f"))
    val h = dummyProject("h", List("f"))
    val i = dummyProject("i", List("h"))
  }

  @Test def TestDagReduction(): Unit = {
    import ComplexDag._
    val allProjects = List(a, b, c, d, e, f, g, h, i)
    val dags = fromMap(allProjects.map(p => p.name -> p).toMap)
    def reduce(targets: Set[Project]): Set[Project] = Dag.reduce(dags, targets)

    Assert.assertEquals("case 1", Set(a, f), reduce(Set(a, f)))
    Assert.assertEquals("case 2", Set(b, f), reduce(Set(b, f)))
    Assert.assertEquals("case 3", Set(d), reduce(Set(c, d)))
    Assert.assertEquals("case 4", Set(d, b), reduce(Set(c, d, b)))
    Assert.assertEquals("case 5", Set(c, b), reduce(Set(c, a, b)))
    Assert.assertEquals("case 6", Set(b), reduce(Set(a, b)))
    Assert.assertEquals("case 7", Set(c, b, h, g), reduce(Set(c, a, b, h, g)))
    Assert.assertEquals("case 8", Set(e, b), reduce(Set(e, b)))
    Assert.assertEquals("case 9", Set(e), reduce(Set(e, a)))
    Assert.assertEquals("case 10", Set(i, g), reduce(Set(i, g)))
    Assert.assertEquals("case 11", Set(i), reduce(Set(i, f)))
    Assert.assertEquals("case 12", Set(i, d, b), reduce(Set(i, f, d, b)))
    Assert.assertEquals("case 13", Set(e), reduce(Set(c, e)))
    Assert.assertEquals("case 14", Set(i, g), reduce(Set(i, h, g, f)))
    Assert.assertEquals("case 14", Set(e, b, i, g), reduce(Set(e, d, c, a, b, i, h, f, g)))
    ()
  }

  @Test
  def TestMinimalInverseDependencies(): Unit = {
    import ComplexDag._
    val allProjects = List(a, b, c, d, e, f, g, h, i)
    val dags = fromMap(allProjects.map(p => p.name -> p).toMap)
    def reduceInverseDeps(targets: List[Project]): Set[Project] =
      Dag.inverseDependencies(dags, targets).reduced.toSet

    Assert.assertEquals("reduced case 1", Set(e, g, i, b), reduceInverseDeps(List(a, f)))
    Assert.assertEquals("reduced case 2", Set(e, g, i, b), reduceInverseDeps(List(e, g, i, b)))
    Assert.assertEquals("reduced case 3", Set(e, b), reduceInverseDeps(List(a, c, d)))
    Assert.assertEquals("reduced case 4", Set(e, b), reduceInverseDeps(List(a, d)))
    Assert.assertEquals("reduced case 5", Set(e, b), reduceInverseDeps(List(c, b)))
    Assert.assertEquals("reduced case 6", Set(e), reduceInverseDeps(List(d)))
    Assert.assertEquals("reduced case 7", Set(e), reduceInverseDeps(List(c)))
    Assert.assertEquals("reduced case 8", Set(g, i), reduceInverseDeps(List(g, h)))
    Assert.assertEquals("reduced case 9", Set(g, i), reduceInverseDeps(List(f)))

    def allInverseDeps(targets: List[Project]): Set[Project] =
      Dag.inverseDependencies(dags, targets).strictlyInverseNodes.toSet

    Assert.assertEquals("all case 1", allProjects.toSet, allInverseDeps(List(a, f)))
    Assert.assertEquals("all case 2", Set(e, g, i, b), allInverseDeps(List(e, g, i, b)))
    Assert.assertEquals("all case 3", Set(a, c, d, e, b), allInverseDeps(List(a, c, d)))
    Assert.assertEquals("all case 4", Set(a, c, d, e, b), allInverseDeps(List(a, c)))
    Assert.assertEquals("all case 5", Set(c, d, e), allInverseDeps(List(c, d)))
    Assert.assertEquals("all case 6", Set(i, h, f, g), allInverseDeps(List(f)))
    Assert.assertEquals("all case 7", Set(i, h), allInverseDeps(List(h, i)))
    Assert.assertEquals("all case 8", Set(i, h), allInverseDeps(List(h)))
    Assert.assertEquals("all case 9", Set(i), allInverseDeps(List(i)))
  }
}
