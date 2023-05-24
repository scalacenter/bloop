package sbt.internal.inc.bloop

import java.{util => ju}

import sbt.internal.inc.javac.AnalyzingJavaCompiler
import xsbti.Position
import xsbti.VirtualFile
import xsbti.VirtualFileRef
import xsbti.compile.ClasspathOptions
import xsbti.compile.JavaCompiler

object ZincInternals {
  import sbt.internal.inc.JavaInterfaceUtil.EnrichOptional
  object ZincExistsStartPos {
    def unapply(position: Position): Option[(Int, Int)] = {
      def asIntPos(opt: ju.Optional[Integer]): Option[Int] = opt.toOption.map(_.toInt)

      // Javac doesn't provide column information, so we just assume beginning of the line
      val rangePosition =
        for { startLine <- asIntPos(position.startLine()) } yield (
          startLine,
          asIntPos(position.startColumn()).getOrElse(0)
        )
      rangePosition.orElse {
        for { line <- asIntPos(position.line()) } yield (
          line,
          asIntPos(position.pointer()).orElse(asIntPos(position.offset())).getOrElse(0)
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
      classpath: Seq[VirtualFile],
      instance: xsbti.compile.ScalaInstance,
      cpOptions: ClasspathOptions,
      lookup: (String => Option[VirtualFile]),
      searchClasspath: Seq[VirtualFile]
  ): JavaCompiler = {
    new AnalyzingJavaCompiler(javac, classpath, instance, cpOptions, lookup, searchClasspath)
  }

  import sbt.internal.inc.Relations
  import sbt.internal.util.Relation
  def copyRelations(
      relations: Relations,
      rebase: VirtualFileRef => VirtualFileRef
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
