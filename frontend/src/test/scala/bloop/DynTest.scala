package bloop

import utest._, framework._

// Thanks Olaf!
trait DynTest extends TestSuite {
  private val myTests = IndexedSeq.newBuilder[(String, () => Unit)]
  def test(name: String)(fun: => Any): Unit = {
    myTests += (name -> (() => fun))
    ()
  }
  final override def tests: Tests = {
    val ts = myTests.result()
    val names = Tree("", ts.map(x => Tree(x._1)): _*)
    val thunks = new TestCallTree(Right(ts.map(x => new TestCallTree(Left(x._2())))))
    Tests.apply(names, thunks)
  }
}
