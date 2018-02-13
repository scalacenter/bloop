package bloop.io

import java.nio.file.Files

import org.junit.Test
import org.junit.Assert.{assertFalse, assertTrue}

import bloop.Project
import bloop.tasks.ProjectHelpers.withTemporaryDirectory

class DirChecksumSpec {

  @Test
  def anEmptyDirectoryShouldntChange(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val checksum = DirChecksum(path, Project.loadPattern)
      assertFalse("The checksum shouldn't have been changed.", checksum.changed())
    }
  }

  @Test
  def shouldOnlyTrackMatchedFiles(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val checksum = DirChecksum(path, "glob:**.hello")
      val unmatched = tmp.resolve("test.scala")
      Files.write(unmatched, "test".getBytes)
      assertFalse("The checksum shouldn't have been changed.", checksum.changed())
    }
  }

  @Test
  def shouldDetectNewFiles(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val checksum = DirChecksum(path, "glob:**.scala")
      val matched = tmp.resolve("test.scala")
      Files.write(matched, "test".getBytes)
      assertTrue("The checksum should have been changed.", checksum.changed())
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

      assertTrue("The checksum should have been changed.", checksum.changed())
    }
  }

  @Test
  def shoulntReportUnchangedContent(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val matched = tmp.resolve("test.scala")
      Files.write(matched, "test".getBytes)

      val checksum = DirChecksum(path, "glob:**.scala")
      assertFalse("The checksum shouldn't have been changed.", checksum.changed())

      Files.write(matched, "foo".getBytes)
      Files.write(matched, "test".getBytes)
      assertFalse("The checksum shouldn't have been changed.", checksum.changed())
    }
  }
}
