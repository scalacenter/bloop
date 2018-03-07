package bloop.io

import java.nio.file.Files
import java.nio.file.attribute.FileTime

import org.junit.Test
import org.junit.Assert.{assertEquals, fail}

import bloop.Project
import bloop.tasks.ProjectHelpers.withTemporaryDirectory

class DirChecksumSpec {

  @Test
  def anEmptyDirectoryShouldntChange(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val checksum = DirChecksum(path, Project.loadPattern)
      assertEquals(DirChecksum.DirUnchanged(None), checksum.changed())
    }
  }

  @Test
  def shouldOnlyTrackMatchedFiles(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val checksum = DirChecksum(path, "glob:**.hello")
      val unmatched = tmp.resolve("test.scala")
      Files.write(unmatched, "test".getBytes)
      assertEquals(DirChecksum.DirUnchanged(None), checksum.changed())
    }
  }

  @Test
  def shouldDetectNewFiles(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val checksum = DirChecksum(path, "glob:**.scala")
      val matched = tmp.resolve("test.scala")
      Files.write(matched, "test".getBytes)
      assertEquals(DirChecksum.DirChanged, checksum.changed())
    }
  }

  @Test
  def shouldDetectDeletedFiles(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val matched = tmp.resolve("test.scala")
      Files.write(matched, "test".getBytes)
      val checksum = DirChecksum(path, "glob:**.scala")
      Files.delete(matched)
      assertEquals(DirChecksum.DirChanged, checksum.changed())
    }
  }

  @Test
  def shouldntReportUnchangedContent(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val matched = tmp.resolve("test.scala")
      Files.write(matched, "test".getBytes)

      val checksum = DirChecksum(path, "glob:**.scala")
      assertEquals(DirChecksum.DirUnchanged(None), checksum.changed())

      Files.write(matched, "foo".getBytes)
      Files.write(matched, "test".getBytes)
      val now = FileTime.fromMillis(System.currentTimeMillis() + 5000)
      Files.setLastModifiedTime(matched, now);

      checksum.changed match {
        case DirChecksum.DirUnchanged(Some(newChecksum)) =>
          assertEquals(DirChecksum.DirUnchanged(None), newChecksum.changed())
        case other =>
          fail(s"Expected `DirUnchanged(Some(newChecksum))`, found `$other`")
      }
    }
  }
}
