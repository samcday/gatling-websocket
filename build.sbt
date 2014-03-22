name := "gatling-websocket"

organization := "com.giltgroupe.util"

version := "0.0.9"

scalaVersion := "2.10.3"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-encoding", "utf8")

javacOptions ++= Seq("-Xlint:deprecation", "-encoding", "utf8", "-XX:MaxPermSize=256M")

crossPaths := false

libraryDependencies ++= Seq(
  "com.ning" % "async-http-client" % "1.7.18.20130621",
  "io.gatling.highcharts" % "gatling-charts-highcharts" % "2.0.0-M3a",
  "io.gatling" % "gatling-app" % "2.0.0-M3a"
)

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.11" % "test",
  "org.mockito" % "mockito-core" % "1.9.5" % "test",
  "org.specs2" %% "specs2" % "1.13" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.1.4" % "test"
)

resolvers ++= Seq(
  "Gatling repo" at "http://repository.excilys.com/content/groups/public",
  "typesafe" at "http://repo.typesafe.com/typesafe/releases"
)
