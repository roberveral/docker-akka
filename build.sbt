name := "docker-akka"

version := "1.0"

organization := "com.github.roberveral"

resolvers ++= Seq("Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "Sonatype snapshots"  at "http://oss.sonatype.org/content/repositories/snapshots/")

resolvers += Resolver.bintrayRepo("hseeberger", "maven")

resolvers += Resolver.bintrayRepo("tecsisa", "maven-bintray-repo")


libraryDependencies ++= {
  val akkaVersion = "2.4.14"
  Seq(
    "com.typesafe.akka" %%  "akka-actor"              % akkaVersion,
    "com.typesafe.akka" %%  "akka-slf4j"              % akkaVersion,

    "com.typesafe.akka" %%  "akka-remote"             % akkaVersion,
    "com.typesafe.akka" %%  "akka-cluster"            % akkaVersion,
    "com.typesafe.akka" %%  "akka-cluster-metrics"    % akkaVersion,
    "com.typesafe.akka" %%  "akka-cluster-tools"      % akkaVersion,
    "com.typesafe.akka" %%  "akka-cluster-sharding"   % akkaVersion,
    "com.typesafe.akka" %%  "akka-persistence"        % akkaVersion,
    "com.typesafe.akka" %%  "akka-persistence-cassandra" % "0.22",

    "com.typesafe.akka" %%  "akka-multi-node-testkit" % akkaVersion % "test",

    "com.typesafe.akka" %%  "akka-testkit"                       % akkaVersion % "test",
    "org.scalatest"     %%  "scalatest"                          % "3.0.0"     % "test",
    "com.typesafe.akka" %%  "akka-http-core"                     % "2.4.11",
    "com.typesafe.akka" %%  "akka-http-experimental"             % "2.4.11",
    "com.typesafe.akka" %%  "akka-http-spray-json-experimental"  % "2.4.11",
    "ch.qos.logback"    %   "logback-classic"                    % "1.1.6",
    "com.spotify" % "docker-client" % "6.1.1",
    "de.heikoseeberger" %% "constructr" % "0.15.0",
    "com.tecsisa" %% "constructr-coordination-consul" % "0.5.1"
  )
}

// Assembly settings
mainClass in Global := Some("com.github.roberveral.dockerakka.DockerAkka")

jarName in assembly := "docker-akka.jar"