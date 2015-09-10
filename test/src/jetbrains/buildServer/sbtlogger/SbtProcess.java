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

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SbtProcess {

    public static int runAndTest(String sbtCommands, String workingDir, String... outputFiles) throws IOException, InterruptedException {
        return runSbtAndTest(true,"--error", sbtCommands,workingDir,outputFiles);
    }
    public static int runAndTestWithoutApplyWithLogLevel(String sbtCommands, String logLevel, String workingDir, String... outputFiles) throws IOException, InterruptedException {
        return runSbtAndTest(false,logLevel, sbtCommands,workingDir,outputFiles);
    }

    public static int runAndTestWithLogLevel(String sbtCommands, String logLevel, String workingDir, String... outputFiles) throws IOException, InterruptedException {
        return runSbtAndTest(true,logLevel, sbtCommands,workingDir,outputFiles);
    }

    public static int runWithoutApplyAndTest(String sbtCommands, String workingDir, String... outputFiles) throws IOException, InterruptedException {
        return runSbtAndTest(false,"--error", sbtCommands,workingDir,outputFiles);
    }

    private static int runSbtAndTest(boolean applyPlugin, String logLevel, String sbtCommands, String workingDir, String... outputFiles) throws IOException,
            InterruptedException {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome +
                File.separator + "bin" +
                File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        String ourResourceFolder = "test";
        String sbtPath = new File(ourResourceFolder + File.separator + "sbt").getAbsolutePath();
        String sbtLauncherPath = new File(sbtPath, "bin" + File.separator + "sbt-launch.jar").getAbsolutePath();
        String sbtTcLoggerPluginPath = new File(ourResourceFolder + File.separator + "tc_plugin" + File.separator + "sbt-teamcity-logger.jar").getAbsolutePath();

        String sbtParam = "-Dsbt.log.noformat=true";

        String applyCommand = applyPlugin ? "apply -cp \"" + sbtTcLoggerPluginPath + "\" jetbrains.buildServer.sbtlogger.SbtTeamCityLogger" : "";
        String[] commands = sbtCommands.split(" ");
        String[] utilityCommands = new String[]{javaBin, "-Xmx512m", "-XX:MaxPermSize=256m", "-cp", classpath, "-jar", sbtLauncherPath,
                sbtParam, applyCommand, logLevel};
        String[] fullListOfCommands = new String[utilityCommands.length + commands.length];
        System.arraycopy(utilityCommands, 0, fullListOfCommands, 0, utilityCommands.length);
        System.arraycopy(commands, 0, fullListOfCommands, utilityCommands.length, commands.length);
        ProcessBuilder builder = new ProcessBuilder(fullListOfCommands);

        Map<String, String> env = builder.environment();
        env.put("TEAMCITY_VERSION", "9.0.TEST");
        builder.directory(new File(workingDir));
        Process process = builder.start();
        BufferedReader stdInput = new BufferedReader(new
                InputStreamReader(process.getInputStream()));

        BufferedReader stdError = new BufferedReader(new
                InputStreamReader(process.getErrorStream()));

        File excludes = new File(workingDir + File.separator + "excludes.txt");
        BufferedReader brExcludes = null;
        if (excludes.exists()) {
            brExcludes = new BufferedReader(new FileReader(excludes));
        }

        if (outputFiles == null || outputFiles.length == 0) {
            outputFiles = new String[]{"output.txt"};
        }

        BufferedReader[] readers = new BufferedReader[outputFiles.length];
        for (int i = 0; i < outputFiles.length; i++) {
            readers[i] = new BufferedReader(new FileReader(workingDir + File.separator + outputFiles[i]));
        }
        checkOutputTest(stdInput, brExcludes, readers);


        process.waitFor();

        String s;
        // read any errors from the attempted command
        System.out.println("Here is the standard error of the command (if any):\n");
        while ((s = stdError.readLine()) != null) {
            System.out.println(s);
        }

        return process.exitValue();
    }


    public static void checkOutputTest(BufferedReader stdInput, BufferedReader brExcludes, BufferedReader... requiredOutput) throws IOException {


        String s;

        List<String> allLines = new ArrayList<String>();

        List<Pattern> excludes = getPatterns(brExcludes);

        List<String> excludesFound = new ArrayList<String>();
        //read output
        while ((s = stdInput.readLine()) != null) {
            System.out.println(s);
            allLines.add(s);
            //check for excludes
            for (Pattern exclude : excludes) {
                Matcher excludeMatcher = exclude.matcher(s);
                if (excludeMatcher.find()) {
                    excludesFound.add(s);
                }
            }
        }

        if (brExcludes != null && excludes.size() > 0 && excludesFound.size() > 0) {
            System.out.println("===================== ERROR ==========================");
            System.out.println("The following lines were found but should not be there:");
            for (String ef : excludesFound) {
                System.out.println(ef);
            }
            Assert.assertEquals(excludesFound.size(), 0);
        }


        for (BufferedReader reader : requiredOutput) {
            int i = 0;
            int found = 0;

            System.out.println("=== Check file ===");
            List<Pattern> required = getPatterns(reader);
            assert required.size() > 0;
            Pattern currentRequired = required.get(i++);

            for (String line : allLines) {
                Matcher matcher = currentRequired.matcher(line);
                if (matcher.find()) {
                    found++;
                    if (i < required.size()) {
                        currentRequired = required.get(i++);
                    }
                }
            }

            if (found != required.size()) {
                System.out.println("First failed line:");
                System.out.println(currentRequired);
            }
            Assert.assertEquals(required.size(), found);
        }

    }

    private static List<Pattern> getPatterns(BufferedReader requiredOutput) throws IOException {
        if (requiredOutput == null) {
            return Collections.emptyList();
        }
        List<Pattern> required = new ArrayList<Pattern>();
        String s;
        while ((s = requiredOutput.readLine()) != null) {
            required.add(Pattern.compile(s));
        }
        return required;
    }


}