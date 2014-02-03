package jetbrains.buildServer.sbt;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class SbtLoggerOutputTest {

    @Test
    public void testCompileErrorOutput() throws IOException, InterruptedException {
        SbtProcess.exec("compile",new File("test/testdata/compileerror").getAbsolutePath());
    }

    @Test
    public void testMultiProjectsOutput() throws IOException, InterruptedException {
        SbtProcess.exec("compile",new File("test/testdata/multiproject").getAbsolutePath());
    }

    @Test
    public void testScalaTest() throws IOException, InterruptedException {
        SbtProcess.exec("test",new File("test/testdata/testsupport/scalatest").getAbsolutePath());
    }
}
