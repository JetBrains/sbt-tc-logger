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

package jetbrains.buildServer.sbt;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class SbtLoggerOutputTest {

    @Test
    public void testCompileErrorOutput() throws IOException, InterruptedException {
        SbtProcess.runAndTest("compile", new File("test/testdata/compileerror").getAbsolutePath());

    }

    @Test
    public void testMultiProjectsOutput() throws IOException, InterruptedException {
        SbtProcess.runAndTest("compile", new File("test/testdata/multiproject").getAbsolutePath());
    }

    @Test
    public void testScalaTest() throws IOException, InterruptedException {
        SbtProcess.runAndTest("test", new File("test/testdata/testsupport/scalatest").getAbsolutePath());
    }

    /**
     * Service method. Allows quickly investigate test cases failed directly on TeamCity agent.
     * Agent output should be placed in test data directory and could be checked against required output
     * @throws IOException
     */
    public void testServerLogs() throws IOException {
        String workingDir = new File("test/testdata/multiproject").getAbsolutePath();
        File requiredFile = new File(workingDir + File.separator + "output.txt");
        File serverLogs = new File(workingDir + File.separator + "server_logs.log");
        SbtProcess.checkOutputTest(new BufferedReader(new FileReader(serverLogs)), new BufferedReader(new FileReader(requiredFile)));
    }

}
