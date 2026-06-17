

package jetbrains.buildServer.sbtlogger;


import junit.framework.Assert;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SbtProcess {
    private static final String REPO_ROOT_PROPERTY = "sbt.tc.repo.root";
    private static final String SBT_013_LAUNCHER_PROPERTY = "sbt.tc.sbt.launcher.013";
    private static final String SBT_1_LAUNCHER_PROPERTY = "sbt.tc.sbt.launcher.1";
    private static final String PLUGIN_013_PROPERTY = "sbt.tc.plugin.013";
    private static final String PLUGIN_1_PROPERTY = "sbt.tc.plugin.1";
    private static final String JAVA_HOME_PROPERTY = "sbt.tc.java.home";

    public static int runAndTest(String sbtCommands, String workingDir, String... outputFiles) throws IOException, InterruptedException {
        return runSbtAndTest(true,"--error", sbtCommands,workingDir,outputFiles);
    }

    public static int runAndTestWithAdditionalParams(String sbtCommands, String params, String workingDir, String... outputFiles) throws IOException, InterruptedException {
        return runSbtAndTest(true,params, sbtCommands,workingDir,outputFiles);
    }

    public static int runWithoutApplyAndTest(String sbtCommands, String workingDir, String... outputFiles) throws IOException, InterruptedException {
        return runSbtAndTest(false,"--error", sbtCommands,workingDir,outputFiles);
    }

    private static int runSbtAndTest(boolean applyPlugin, String params, String sbtCommands, String workingDir, String... outputFiles) throws IOException,
            InterruptedException {
        String javaHome = requiredJavaHome();
        String javaBin = javaHome +
                File.separator + "bin" +
                File.separator + "java";

        File sbtGlobalBase = new File(repoRoot(), "test" + File.separator + "sbt").getAbsoluteFile();
        boolean sbtOneTest = isSbtOneTest(workingDir);
        String sbtLauncherPath = requiredFile(sbtOneTest ? SBT_1_LAUNCHER_PROPERTY : SBT_013_LAUNCHER_PROPERTY).getAbsolutePath();
        String sbtTcLoggerPluginPath = requiredFile(sbtOneTest ? PLUGIN_1_PROPERTY : PLUGIN_013_PROPERTY).getAbsolutePath();

        String sbtGlobalBaseParam = "-Dsbt.global.base=" + sbtGlobalBase.getAbsolutePath();
        String sbtLogParam = "-Dsbt.log.noformat=true";

        String applyCommand = applyPlugin ? "apply -cp \"" + sbtTcLoggerPluginPath + "\" jetbrains.buildServer.sbtlogger.SbtTeamCityLogger" : "";
        String[] commands = sbtCommands.split(" ");
        List<String> fullListOfCommands = new ArrayList<String>();
        Collections.addAll(fullListOfCommands, javaBin, "-Xmx512m", "-XX:MaxPermSize=256m", "-jar", sbtLauncherPath,
                sbtGlobalBaseParam, sbtLogParam);
        addIfNotBlank(fullListOfCommands, applyCommand);
        addIfNotBlank(fullListOfCommands, params);
        Collections.addAll(fullListOfCommands, commands);
        ProcessBuilder builder = new ProcessBuilder(fullListOfCommands);

        Map<String, String> env = builder.environment();
        env.put("TEAMCITY_VERSION", "9.0.TEST");
        env.put("JAVA_HOME", javaHome);
        env.put("SBT_HOME", sbtGlobalBase.getAbsolutePath());

        String path = env.get("PATH");
        String jHome = System.getenv("JDK_HOME");
        if (jHome == null) {
            jHome = javaHome;
        }
        env.put("PATH", (jHome + File.separator + "bin") + (path != null && path.length() > 0 ? File.pathSeparator + path : ""));

        if (params.contains("--debug")) {
            System.out.println("builder.environment()");
            for (Map.Entry<String, String> entry : env.entrySet()) {
                System.out.println(entry.getKey() + " -> " + entry.getValue());
            }
        }

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

        //System.out.println("##teamcity[disableServiceMessages]");
        //read output
        while ((s = stdInput.readLine()) != null) {
            System.out.println(s.replaceAll("##teamcity","##t-e-a-m-c-i-t-y"));
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

        //System.out.println("##teamcity[enableServiceMessages]");


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

    public static File repoRoot() {
        String repoRoot = System.getProperty(REPO_ROOT_PROPERTY);
        if (repoRoot == null || repoRoot.trim().length() == 0) {
            repoRoot = ".";
        }
        return new File(repoRoot).getAbsoluteFile();
    }

    private static File requiredFile(String propertyName) {
        String path = System.getProperty(propertyName);
        if (path == null || path.trim().length() == 0) {
            throw new IllegalStateException("Missing required system property: " + propertyName);
        }

        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalStateException("Required file from system property " + propertyName + " does not exist: " + file.getAbsolutePath());
        }
        return file.getAbsoluteFile();
    }

    private static String requiredJavaHome() {
        String javaHome = firstNotBlank(
                System.getProperty(JAVA_HOME_PROPERTY),
                System.getenv("IT_JAVA_HOME"),
                System.getenv("JAVA_8_HOME")
        );
        if (javaHome == null) {
            throw new IllegalStateException("Integration tests require Java 8 for nested sbt processes. Set IT_JAVA_HOME or JAVA_8_HOME.");
        }

        File javaBin = new File(javaHome, "bin" + File.separator + "java");
        if (!javaBin.isFile()) {
            throw new IllegalStateException("Configured Java home does not contain bin/java: " + new File(javaHome).getAbsolutePath());
        }
        return new File(javaHome).getAbsolutePath();
    }

    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && value.trim().length() > 0) {
                return value;
            }
        }
        return null;
    }

    private static void addIfNotBlank(List<String> list, String value) {
        if (value != null && value.trim().length() > 0) {
            list.add(value);
        }
    }

    private static boolean isSbtOneTest(String workingDir) {
        String normalized = new File(workingDir).getAbsolutePath().replace(File.separatorChar, '/');
        return normalized.contains("/test/testdata/1.0/");
    }

}
