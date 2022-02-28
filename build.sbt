val spinalVersion = "1.6.5"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "uestc-vicbic",
      scalaVersion := "2.11.12",
      version      := "2.0.0"
    )),
    scalacOptions +=  s"-Xplugin:${new File(baseDirectory.value + s"/ext/SpinalHDL/idslplugin/target/scala-2.11/spinalhdl-idsl-plugin_2.11-$spinalVersion.jar")}",
    scalacOptions += s"-Xplugin-require:idsl-plugin",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.5",
      "org.yaml" % "snakeyaml" % "1.8"
    ),
    name := "CacheSNN"
  ).dependsOn(
  spinalHdlIdslPlugin, spinalHdlSim,spinalHdlCore, spinalHdlLib,
)
lazy val spinalHdlIdslPlugin = ProjectRef(file("ext/SpinalHDL"), "idslplugin")
lazy val spinalHdlSim = ProjectRef(file("ext/SpinalHDL"), "sim")
lazy val spinalHdlCore = ProjectRef(file("ext/SpinalHDL"), "core")
lazy val spinalHdlLib = ProjectRef(file("ext/SpinalHDL"), "lib")

fork := true
