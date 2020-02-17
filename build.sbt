import Options._

inThisBuild(Seq(
  version := "0.1.0-SNAPSHOT",

  organization := "com.github.cornerman",

  scalaVersion := "2.12.10",

  crossScalaVersions := Seq("2.12.10", "2.13.1"),
))

lazy val commonSettings = Seq(
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full),
  addCompilerPlugin("com.github.ghik" % "silencer-plugin" % "1.5.0" cross CrossVersion.full),

  libraryDependencies ++= Seq(
    "org.scalatest" %%% "scalatest" % "3.1.0" % Test,
    "com.github.ghik" % "silencer-lib" % "1.5.0" % Provided cross CrossVersion.full,
  ),

  scalacOptions ++= CrossVersion.partialVersion(scalaVersion.value).toList.flatMap { case (major, minor) =>
    versionBasedOptions(s"${major}.${minor}")
  },
  scalacOptions in (Compile, console) ~= (_.diff(badConsoleFlags)),

  publishMavenStyle := true,

  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
    else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },

  pomExtra :=
    <developers>
        <developer>
        <id>jk</id>
        <name>Johannes Karoff</name>
        <url>https://github.com/cornerman</url>
        </developer>
    </developers>,

  pomIncludeRepository := { _ => false }
)

lazy val jsSettings = Seq(
  scalacOptions ++= scalajsOptions,

  scalacOptions += {
    val local = baseDirectory.value.toURI
    val remote = s"https://raw.githubusercontent.com/cornerman/colibri/${git.gitHeadCommit.value.get}/"
    s"-P:scalajs:mapSourceURI:$local->$remote"
  }
)

lazy val colibri = project
  .enablePlugins(ScalaJSPlugin)
  .in(file("colibri"))
  .settings(commonSettings, jsSettings)
  .settings(
    name := "colibri",

    libraryDependencies ++= Seq(
      "org.scala-js"  %%% "scalajs-dom" % "0.9.8",
      "org.typelevel" %%% "cats-core" % "2.1.0",
      "org.typelevel" %%% "cats-effect" % "2.1.1",
    )
  )

lazy val root = project
  .in(file("."))
  .settings(
    name := "colibri-root",

    skip in publish := true,
  )
  .aggregate(colibri)
