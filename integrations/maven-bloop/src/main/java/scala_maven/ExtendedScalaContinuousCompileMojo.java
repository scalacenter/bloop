package scala_maven;

import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ExtendedScalaContinuousCompileMojo extends ScalaContinuousCompileMojo {
    public MavenProject getProject() {
        return super.project;
    }

    public String getScalaVersion() throws Exception {
        return super.findScalaVersion().toString();
    }

    public List<String> getScalacArgs() throws Exception {
        return super.getScalaOptions();
    }

    public List<String> getJavacArgs() throws Exception {
        return super.getJavacOptions();
    }

    public List<File> getCompileSourceDirectories() throws Exception {
        List<String> mainSources = new ArrayList<String>(project.getCompileSourceRoots());
        mainSources.add(FileUtils.pathOf(mainSourceDir, useCanonicalPath));
        return normalize(mainSources);
    }

    public List<File> getTestSourceDirectories() throws Exception {
        List<String> testSources = new ArrayList<String>(project.getTestCompileSourceRoots());
        testSources.add(FileUtils.pathOf(testSourceDir, useCanonicalPath));
        return normalize(testSources);
    }

    public File getCompileOutputDir() throws Exception {
        mainOutputDir = FileUtils.fileOf(mainOutputDir, useCanonicalPath);
        if (!mainOutputDir.exists()) {
            mainOutputDir.mkdirs();
        }
        return mainOutputDir;
    }

    public File getTestOutputDir() throws Exception {
        testOutputDir = FileUtils.fileOf(testOutputDir, useCanonicalPath);
        if (!testOutputDir.exists()) {
            testOutputDir.mkdirs();
        }
        return testOutputDir;
    }

    public File getCompileAnalysisCacheFile() throws Exception {
        return FileUtils.fileOf(analysisCacheFile, useCanonicalPath);
    }

    public File getTestAnalysisCacheFile() throws Exception {
        return FileUtils.fileOf(testAnalysisCacheFile, useCanonicalPath);
    }
}
