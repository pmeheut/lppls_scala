import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.dockerBaseImage

import scala.sys.process._
import NativePackagerHelper._

name := "lppls_scala"
version := "0.1"
// Adding this build is important: we want the same scala version for the dockerPackage project
ThisBuild / scalaVersion := "3.1.0"

// For Docker

import com.typesafe.sbt.packager.docker._

// For scala native but it is not really faster
//scalaVersion := "2.13.7"
//nativeLinkStubs := true
//enablePlugins(ScalaNativePlugin)

lazy val genericLib = "lib"
lazy val macLib = "lib_mac"

lazy val slsqp4jDependency = "com.skew.slsqp4j" % "slsqp4j" % "0.1"

lazy val systemSpecific = System.getProperty("os.name").toLowerCase match {
  case mac if mac.contains("mac") => (s"""${System.getProperty("user.home")}/opt/anaconda3""", macLib, Seq())
  case _ => (s"""${System.getProperty("user.home")}/anaconda3""", genericLib, Seq(slsqp4jDependency))
  //case win if win.contains("win") => s"""${System.getProperty("user.dir")}/ananconda3"""
  //case linux if linux.contains("linux") => "org.example" %% "somelib-linux" % "1.0.0"
  //case osName => throw new RuntimeException(s"Unknown operating system $osName")
}

lazy val anacondaDir = systemSpecific._1
lazy val libraryDir = systemSpecific._2
val platformDependencies = systemSpecific._3

lazy val anacondaEnv = "lbo"
lazy val pythonEnvPath = s"$anacondaDir/envs/$anacondaEnv"
lazy val pythonAnacondaPath = s"$anacondaDir"

// Code does not work because python3-config in Anaconda on Mac returns a wrong directory
lazy val pythonLdFlags = {
  val withoutEmbed = s"${pythonEnvPath}/python3-config --ldflags".!!
  if (withoutEmbed.contains("-lpython")) {
    withoutEmbed.split(' ').map(_.trim).filter(_.nonEmpty).toSeq
  } else {
    val withEmbed = s"${pythonEnvPath}/python3-config --ldflags --embed".!!
    withEmbed.split(' ').map(_.trim).filter(_.nonEmpty).toSeq
  }
}

lazy val pythonLibsDir = {
  pythonLdFlags.find(_.startsWith("-L")).get.drop("-L".length)
}

lazy val javaPythonOptions = s"-Djna.library.path=$pythonEnvPath/lib:$pythonAnacondaPath/lib"
lazy val javaVectorOption = "--add-modules=jdk.incubator.vector"
lazy val javaLoggingOption = "-Djava.util.logging.config.file=/logging.properties"

lazy val lppls = project
  .in(file("."))
  .settings(
    Runtime / unmanagedBase := baseDirectory.value / libraryDir,
    libraryDependencies ++= Seq(
      "me.shadaj" %% "scalapy-core" % "0.5.1" cross CrossVersion.for3Use2_13,
      "org.scalanlp" %% "breeze" % "2.0.1-RC1" cross CrossVersion.for3Use2_13,
      "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4",
      "com.github.scopt" %% "scopt" % "4.0.1" cross CrossVersion.for3Use2_13, // command line arguments parsing
      "com.skew.slsqp4j" % "slsqp4j" % "0.1"
    ),
    run / fork := true,
    run / javaOptions ++= Seq(javaLoggingOption, javaPythonOptions, javaVectorOption)
  )

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)
addCommandAlias("stage", "lpplsDocker / Docker / stage")
addCommandAlias("publishLocal", "lpplsDocker / Docker / publishLocal")
addCommandAlias("dockerClean", "lpplsDocker / Docker / clean")
addCommandAlias("rePublishLocal", "dockerClean; publishLocal")

lazy val lpplsDocker = project
  .in(file("build/prod"))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .settings(
    libraryDependencies += slsqp4jDependency,
    // We do not copy the mac specific libraries
    Runtime / unmanagedBase := (lppls / baseDirectory).value / genericLib,
    // Docker configuration
    // First we remove the useless logging and we increase the heap size
    // JRE options must be passed with -J before (except -D)
    Universal / javaOptions ++= Seq(javaLoggingOption, "-J-Xmx8g", s"-J$javaVectorOption"),
    // If we do not define the package name & version explicitely
    // they will be lpplsDocker-0.1.0-SNAPSHOT and it is not docker compatible on top of being wrong
    Docker / packageName := (lppls / name).value,
    Docker / version := (lppls / version).value,
    Compile / mainClass := Some("ComputeIndicators"),
    dockerBaseImage := "python:3.9-slim-bullseye",
    Docker / daemonUser := "lppls",
    dockerCommands ++= Seq(
      Cmd("USER", "root"),
      ExecCmd("RUN", "apt", "update"),
      ExecCmd("RUN", "apt", "install", "-y", "openjdk-17-jre-headless"),
      ExecCmd("RUN", "pip", "install", "pandas"),
      ExecCmd("RUN", "pip", "install", "feather-format"),
      Cmd("USER", "lppls"),
      ExecCmd("RUN", "mkdir", "-p", "/home/lppls/Dropbox/lbo/crash_detection"),
    ),
    // Uncomment to debug by launching bash instead of the scala program
    //dockerEntrypoint := Seq(s"bash"),
  )
  .dependsOn(lppls)
