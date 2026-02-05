package bloop.io

import java.nio.file.Files

import bloop.logging.RecordingLogger
import bloop.util.TestUtil

object ResourceMapperSpec extends bloop.testing.BaseSuite {

  test("copy single file mapping") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger()

      // Create source file
      val sourceFile = workspace.resolve("source.txt")
      Files.write(sourceFile.underlying, "test content".getBytes("UTF-8"))

      // Define mapping
      val mappings = List((sourceFile, "custom/path/file.txt"))

      // Copy to target directory
      val targetDir = workspace.resolve("target")
      Files.createDirectories(targetDir.underlying)

      val task = ResourceMapper.copyMappedResources(mappings, targetDir, logger)
      TestUtil.await(5, java.util.concurrent.TimeUnit.SECONDS)(task)

      // Verify file was copied
      val copiedFile = targetDir.resolve("custom/path/file.txt")
      assert(copiedFile.exists)
      assert(copiedFile.isFile)

      val content = new String(Files.readAllBytes(copiedFile.underlying), "UTF-8")
      assertNoDiff(content, "test content")
    }
  }

  test("copy directory mapping recursively") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger()

      // Create source directory with files
      val sourceDir = workspace.resolve("source-dir")
      Files.createDirectories(sourceDir.underlying)

      val file1 = sourceDir.resolve("file1.txt")
      val file2 = sourceDir.resolve("subdir/file2.txt")
      Files.createDirectories(file2.getParent.underlying)

      Files.write(file1.underlying, "content 1".getBytes("UTF-8"))
      Files.write(file2.underlying, "content 2".getBytes("UTF-8"))

      // Define mapping
      val mappings = List((sourceDir, "data"))

      // Copy to target directory
      val targetDir = workspace.resolve("target")
      Files.createDirectories(targetDir.underlying)

      val task = ResourceMapper.copyMappedResources(mappings, targetDir, logger)
      TestUtil.await(5, java.util.concurrent.TimeUnit.SECONDS)(task)

      // Verify directory structure was copied
      val copiedFile1 = targetDir.resolve("data/file1.txt")
      val copiedFile2 = targetDir.resolve("data/subdir/file2.txt")

      assert(copiedFile1.exists)
      assert(copiedFile2.exists)

      val content1 = new String(Files.readAllBytes(copiedFile1.underlying), "UTF-8")
      val content2 = new String(Files.readAllBytes(copiedFile2.underlying), "UTF-8")

      assertNoDiff(content1, "content 1")
      assertNoDiff(content2, "content 2")
    }
  }

  test("handle empty mappings gracefully") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger()

      val targetDir = workspace.resolve("target")
      Files.createDirectories(targetDir.underlying)

      val task = ResourceMapper.copyMappedResources(List.empty, targetDir, logger)
      TestUtil.await(5, java.util.concurrent.TimeUnit.SECONDS)(task)

      // Should complete without error
      assert(logger.errors.isEmpty)
    }
  }

  test("handle non-existent source file") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger()

      val nonExistentFile = workspace.resolve("does-not-exist.txt")
      val mappings = List((nonExistentFile, "output.txt"))

      val targetDir = workspace.resolve("target")
      Files.createDirectories(targetDir.underlying)

      val task = ResourceMapper.copyMappedResources(mappings, targetDir, logger)
      TestUtil.await(5, java.util.concurrent.TimeUnit.SECONDS)(task)

      // Should warn about missing source
      assert(logger.warnings.exists(_.contains("does not exist")))

      // Target file should not be created
      val targetFile = targetDir.resolve("output.txt")
      assert(!targetFile.exists)
    }
  }

  test("detect changes in mapped files") {
    TestUtil.withinWorkspace { workspace =>
      val file = workspace.resolve("file.txt")
      Files.write(file.underlying, "original content".getBytes("UTF-8"))

      val oldTimestamp = System.currentTimeMillis() - 10000 // 10 seconds ago
      val mappings = List((file, "output.txt"))

      // File is newer than timestamp
      val changed = ResourceMapper.hasMappingsChanged(mappings, oldTimestamp)
      assert(changed)

      // File is older than timestamp
      val futureTimestamp = System.currentTimeMillis() + 10000 // 10 seconds in future
      val notChanged = ResourceMapper.hasMappingsChanged(mappings, futureTimestamp)
      assert(!notChanged)
    }
  }

  test("detect changes in mapped directories") {
    TestUtil.withinWorkspace { workspace =>
      val dir = workspace.resolve("source-dir")
      Files.createDirectories(dir.underlying)

      val file = dir.resolve("file.txt")
      Files.write(file.underlying, "content".getBytes("UTF-8"))

      val oldTimestamp = System.currentTimeMillis() - 10000 // 10 seconds ago
      val mappings = List((dir, "data"))

      // Directory contains file newer than timestamp
      val changed = ResourceMapper.hasMappingsChanged(mappings, oldTimestamp)
      assert(changed)
    }
  }
}
