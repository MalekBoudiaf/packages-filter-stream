ThisBuild / version := "0.0.1"
ThisBuild / scalaVersion := "3.2.0"

lazy val root = (project in file("."))
  .settings(
    name := "Assignment1_22_23"
  )

val AKKA_VERSION = "2.7.0"

libraryDependencies ++= Seq(
  // Akka Libraries
  "com.typesafe.akka" %% "akka-stream" % AKKA_VERSION,
  "com.typesafe.akka" %% "akka-actor" % AKKA_VERSION,
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",

  // Requesting and processing data
  "com.lihaoyi" %% "requests" % "0.7.1",
  "com.lihaoyi" %% "ujson" % "2.0.0"
)
