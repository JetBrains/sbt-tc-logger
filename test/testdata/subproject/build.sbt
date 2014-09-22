lazy val backend = project.in(file("backend"))

lazy val root = project.in(file(".")).aggregate(backend)


