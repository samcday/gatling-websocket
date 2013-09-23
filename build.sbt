name := "gatling-websocket"

organization := "com.giltgroupe.util"

version := "0.0.9"

scalaVersion := "2.9.3"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-encoding", "utf8")

javacOptions ++= Seq("-Xlint:deprecation", "-encoding", "utf8", "-XX:MaxPermSize=256M")

crossPaths := false

libraryDependencies ++= Seq(
  "com.ning" % "async-http-client" % "1.7.18.20130621",
  "com.excilys.ebi.gatling" % "gatling-core" % "1.5.2",
  "com.excilys.ebi.gatling" % "gatling-http" % "1.5.2"
)

libraryDependencies ++= Seq(
  "junit" % "junit" % "4.11" % "test",
  "org.mockito" % "mockito-core" % "1.9.5" % "test",
  "org.specs2" %% "specs2" % "1.12.4.1" % "test",
  "com.typesafe.akka" % "akka-testkit" % "2.0.4" % "test"
)

resolvers ++= Seq(
  "excilys" at "http://repository.excilys.com/content/groups/public",
  "typesafe" at "http://repo.typesafe.com/typesafe/releases"
)
