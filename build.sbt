ThisBuild / version := "1.0"
ThisBuild / scalaVersion := "2.11.12"
ThisBuild / organization := "org.example"

val spinalVersion = "1.6.4"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
val scalaTest = "org.scalatest" %% "scalatest" % "3.2.0" % "test"

lazy val mylib = (project in file("."))
  .settings(
    name := "cacheSNN",
    libraryDependencies ++= Seq(scalaTest, spinalCore, spinalLib, spinalIdslPlugin)
  )

fork := true
