ThisBuild / scalaVersion := "2.13.18"

lazy val left = project.in(file("left"))
lazy val right = project.in(file("right"))
lazy val root = project.in(file(".")).aggregate(left, right)
