package sbt.internal.inc.bloop

import java.io.File
import java.{util => ju}
import java.nio.file.Files

import bloop.ScalaInstance
import bloop.io.AbsolutePath
import sbt.internal.inc.BloopComponentCompiler
import sbt.internal.inc.javac.AnalyzingJavaCompiler
import sbt.librarymanagement.{Configurations, ModuleID}
import xsbti.compile.{ClasspathOptions, JavaCompiler}
import xsbti.{ComponentProvider, Position}

object ZincInternals {
  import sbt.internal.inc.JavaInterfaceUtil.EnrichOptional
  object ZincExistsStartPos {
    def unapply(position: Position): Option[(Int, Int)] = {
      def asIntPos(opt: ju.Optional[Integer]): Option[Int] = opt.toOption.map(_.toInt)

      // Javac doesn't provide column information, so we just assume beginning of the line
      val rangePosition = for { startLine <- asIntPos(position.startLine()) } yield (
        startLine,
        asIntPos(position.startColumn()).getOrElse(0)
      )
      rangePosition.orElse {
        for { line <- asIntPos(position.line()) } yield (
          line,
          asIntPos(position.pointer()).getOrElse(0)
        )
      }
    }
  }

  object ZincRangePos {
    def unapply(position: Position): Option[(Int, Int)] = {
      position.endLine.toOption
        .flatMap(endLine => position.endColumn().toOption.map(endColumn => (endLine, endColumn)))
    }
  }

  def instantiateJavaCompiler(
      javac: xsbti.compile.JavaCompiler,
      classpath: Seq[File],
      instance: xsbti.compile.ScalaInstance,
      cpOptions: ClasspathOptions,
      lookup: (String => Option[File]),
      searchClasspath: Seq[File]
  ): JavaCompiler = {
    new AnalyzingJavaCompiler(javac, classpath, instance, cpOptions, lookup, searchClasspath)
  }

  import sbt.internal.inc.Relations
  import sbt.internal.util.Relation
  def copyRelations(
      relations: Relations,
      rebase: File => File
  ): Relations = {
    val newSrcProd = Relation.empty ++ {
      relations.srcProd.all.map {
        case (src, classFile) => src -> rebase(classFile)
      }
    }

    Relations.make(
      newSrcProd,
      relations.libraryDep,
      relations.libraryClassName,
      relations.internalDependencies,
      relations.externalDependencies,
      relations.classes,
      relations.names,
      relations.productClassName
    )
  }
}
