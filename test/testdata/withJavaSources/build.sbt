mainClass in (Compile, run) := Some("com.jetbrains.sbt.test.HelloWorld")

javaHome := sys.env.get("JAVA_HOME") map file