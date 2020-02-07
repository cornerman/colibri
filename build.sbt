import Options._


inThisBuild(Seq(
  version := "0.1.0-SNAPSHOT",

  organization := "com.github.cornerman",

  scalaVersion := "2.12.10",

  crossScalaVersions := Seq("2.12.10", "2.13.1"),
))

lazy val commonSettings = Seq(
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full),
  addCompilerPlugin("com.github.ghik" % "silencer-plugin" % "1.4.4" cross CrossVersion.full),

  requireJsDomEnv in Test := true,

  useYarn := true,

  libraryDependencies ++= Seq(
    "org.scalatest" %%% "scalatest" % "3.1.0" % Test,
    "com.github.ghik" % "silencer-lib" % "1.4.4" % Provided cross CrossVersion.full,
  ),

  scalacOptions ++= CrossVersion.partialVersion(scalaVersion.value).map(v =>
    allOptionsForVersion(s"${v._1}.${v._2}", true)
  ).getOrElse(Nil),
  scalacOptions in (Compile, console) ~= (_.diff(badConsoleFlags)),

  scalacOptions += {
    val local = baseDirectory.value.toURI
    val remote = s"https://raw.githubusercontent.com/cornerman/colibri/${git.gitHeadCommit.value.get}/"
    s"-P:scalajs:mapSourceURI:$local->$remote"
  },

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

lazy val colibri = project
  .enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin)
  .in(file("colibri"))
  .settings(commonSettings)
  .settings(
    name := "colibri",
    normalizedName := "colibri",

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
