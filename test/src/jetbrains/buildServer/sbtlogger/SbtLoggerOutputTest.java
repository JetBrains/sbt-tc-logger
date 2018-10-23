/*
 * Copyright 2013-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */

package jetbrains.buildServer.sbtlogger;

import junit.framework.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class SbtLoggerOutputTest {


    @Test
    public void testPluginStatus() throws IOException, InterruptedException {
        SbtProcess.runAndTest("sbt-teamcity-logger", testPath("compileerror"),"plugin_status_output.txt");

    }

    @Test
    public void testCompileErrorOutput() throws IOException, InterruptedException {
        SbtProcess.runAndTest("compile", testPath("compileerror"));

    }

    @Test
    public void testCompileSuccessfulOutput() throws IOException, InterruptedException {
        SbtProcess.runAndTest("compile", testPath("compilesuccessful"));

    }

    @Test
    public void testMultiProjectsOutput() throws IOException, InterruptedException {
        SbtProcess.runAndTest("compile", testPath("multiproject"));
    }


    @Test
    public void testTmp() throws IOException, InterruptedException {
        SbtProcess.runAndTestWithAdditionalParams("--debug","compile", testPath("multiproject"));
    }

    @Test
    public void testScalaTest() throws IOException, InterruptedException {
        int exitCode = SbtProcess.runAndTest("test", testPath("testsupport/scalatest"), "output.txt", "output1.txt");
        //if need exit code equals 0, otherwise in TeamCity additional non-informative build problem message will appear
        Assert.assertEquals(0,exitCode);
    }

    @Test
    public void testNoSbtFileInProject() throws IOException, InterruptedException {
        SbtProcess.runAndTest("compile", testPath("nosbtfile"));
    }

    @Test
    public void testWarningInTestOutput() throws IOException, InterruptedException {
        SbtProcess.runAndTest("test", testPath("TW35693"));
    }

    @Test
    public void testTW35404_error() throws IOException, InterruptedException {
        SbtProcess.runAndTest("compile", testPath("TW35404_error"));
    }

    @Test
    public void testTW35404_debug() throws IOException, InterruptedException {
        SbtProcess.runAndTest("compile", testPath("TW35404_debug"));
    }

    @Test
    public void testSubProject_compile() throws IOException, InterruptedException {
        SbtProcess.runAndTest("backend/compile", testPath("subproject"));
    }

    @Test
    public void testRunTestWithSbt() throws IOException, InterruptedException {
        int exitCode = SbtProcess.runAndTest("test", testPath("testsupport/scalatest"), "output.txt", "output1.txt");
        //if need exit code equals 0, otherwise in TeamCity additional non-informative build problem message will appear
        Assert.assertEquals(0,exitCode);
    }

    @Test
    public void testRunWithPluginFromBintray() throws IOException, InterruptedException {
        SbtProcess.runWithoutApplyAndTest("test", testPath("bintray"));
    }


    @Test
    public void testRunWithPluginFromBintrayWithReApply() throws IOException, InterruptedException {
        SbtProcess.runAndTest("test", testPath("bintray"));
    }

    @Test
    public void testOtherSbtVersions() throws IOException, InterruptedException {
        SbtProcess.runAndTestWithAdditionalParams("sbtVersion", "--info", testPath("otherVersions"));
    }

    @Test
    public void testProjectWithJavaSources() throws IOException, InterruptedException {
        SbtProcess.runAndTestWithAdditionalParams("clean compile run",  "--debug", testPath("withJavaSources"),"output.txt");
    }

    @Test
    public void testIgnoredTest() throws IOException, InterruptedException {
        SbtProcess.runAndTestWithAdditionalParams("--info", "test", testPath("ignoredTest"));
    }

    @Test
    public void testNestedSuites() throws IOException, InterruptedException {
        SbtProcess.runAndTestWithAdditionalParams("--info", "test", testPath("testsupport/nested"));
    }

    @Test
    public void testSpecTW46964() throws IOException, InterruptedException {
        SbtProcess.runAndTest("testOnly", testPath("testsupport/scalatest_TW46964"),"output.txt");
    }


    @Test
    public void testSpec2() throws IOException, InterruptedException {
        SbtProcess.runAndTest("testOnly", testPath("testsupport/spec2"),"output.txt");
    }

    @Test
    public void testTW50753_initErrorInTests() throws IOException, InterruptedException {
        SbtProcess.runAndTest("clean compile test", testPath("TW-50753_initErrorInTests"),"output.txt");
    }


    @Test
    public void testParallelTestExecutionTW43578() throws IOException, InterruptedException {
        SbtProcess.runAndTestWithAdditionalParams("--info", "test",
                testPath("testsupport/parallelTestExecutionTW43578/src/"),
                "output.txt","output1.txt", "output2.txt","output3.txt","output4.txt","output5.txt", "output7.txt",
                "output6.txt", "output8.txt", "output9.txt", "output10.txt", "output11.txt");
    }
    /**
     * Service method. Allows quickly investigate test cases failed directly on TeamCity agent.
     * Agent output should be placed in test data directory and could be checked against required output
     */
    public void testServerLogs() throws IOException {
        String workingDir = testPath("multiproject");
        File requiredFile = new File(workingDir + File.separator + "output.txt");
        File serverLogs = new File(workingDir + File.separator + "server_logs.log");
        SbtProcess.checkOutputTest(new BufferedReader(new FileReader(serverLogs)), new BufferedReader(new FileReader(requiredFile)), (BufferedReader) null);
    }

    private String testPath(String testRepo) {
        File testDir = new File("test/testdata/", testRepo);
        return testDir.getAbsolutePath();
    }

}
