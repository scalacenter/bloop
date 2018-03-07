package bloop.io

import java.nio.file.Files
import java.nio.file.attribute.FileTime

import org.junit.Test
import org.junit.Assert.{assertEquals, fail}

import bloop.Project
import bloop.tasks.ProjectHelpers.withTemporaryDirectory

class FileTrackerSpec {

  @Test
  def anEmptyDirectoryShouldntChange(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val checksum = FileTracker(path, Project.loadPattern)
      assertEquals(FileTracker.Unchanged(None), checksum.changed())
    }
  }

  @Test
  def shouldOnlyTrackMatchedFiles(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val checksum = FileTracker(path, "glob:**.hello")
      val unmatched = tmp.resolve("test.scala")
      Files.write(unmatched, "test".getBytes)
      assertEquals(FileTracker.Unchanged(None), checksum.changed())
    }
  }

  @Test
  def shouldDetectNewFiles(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val checksum = FileTracker(path, "glob:**.scala")
      val matched = tmp.resolve("test.scala")
      Files.write(matched, "test".getBytes)
      assertEquals(FileTracker.Changed, checksum.changed())
    }
  }

  @Test
  def shouldDetectDeletedFiles(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val matched = tmp.resolve("test.scala")
      Files.write(matched, "test".getBytes)
      val checksum = FileTracker(path, "glob:**.scala")
      Files.delete(matched)
      assertEquals(FileTracker.Changed, checksum.changed())
    }
  }

  @Test
  def shouldntReportUnchangedContent(): Unit = {
    withTemporaryDirectory { tmp =>
      val path = AbsolutePath(tmp)
      val matched = tmp.resolve("test.scala")
      Files.write(matched, "test".getBytes)

      val checksum = FileTracker(path, "glob:**.scala")
      assertEquals(FileTracker.Unchanged(None), checksum.changed())

      Files.write(matched, "foo".getBytes)
      Files.write(matched, "test".getBytes)
      val now = FileTime.fromMillis(System.currentTimeMillis() + 5000)
      Files.setLastModifiedTime(matched, now);

      checksum.changed match {
        case FileTracker.Unchanged(Some(newChecksum)) =>
          assertEquals(FileTracker.Unchanged(None), newChecksum.changed())
        case other =>
          fail(s"Expected `Unchanged(Some(newChecksum))`, found `$other`")
      }
    }
  }
}
