ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.3"

libraryDependencies += "co.fs2" %% "fs2-io" % "3.10.2"

lazy val root = (project in file("."))
  .settings(
    name := "mavenPackageUploader"
  )
