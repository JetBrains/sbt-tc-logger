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

import java.io.File;
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
}
