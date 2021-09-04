inThisBuild(Seq(
  version := "0.1.0-SNAPSHOT",

  organization := "com.github.cornerman",

  scalaVersion := "2.12.14",

  crossScalaVersions := Seq("2.12.14", "2.13.6"),
))

lazy val commonSettings = Seq(
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),

  libraryDependencies ++= Seq(
    "org.scalatest" %%% "scalatest" % "3.2.9" % Test,
  ),

  publishMavenStyle := true,

  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
    else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },

  resolvers ++=
      ("jitpack" at "https://jitpack.io") ::
      Nil,

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
      "org.scala-js"  %%% "scalajs-dom" % "1.2.0",
      "org.typelevel" %%% "cats-core" % "2.6.1",
      "org.typelevel" %%% "cats-effect" % "2.5.3",
    )
  )

lazy val router = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(colibri)
  .in(file("router"))
  .settings(commonSettings, jsSettings)
  .settings(
    name := "colibri-router",

    libraryDependencies ++= Seq(
    )
  )

lazy val monix = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(colibri)
  .in(file("monix"))
  .settings(commonSettings, jsSettings)
  .settings(
    name := "colibri-monix",

    libraryDependencies ++= Seq(
      "io.monix"      %%% "monix"       % "3.4.0",
    )
  )

lazy val rx = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(colibri)
  .in(file("rx"))
  .settings(commonSettings, jsSettings)
  .settings(
    name := "colibri-rx",

    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "scalarx" % "0.4.3"
    )
  )

lazy val root = project
  .in(file("."))
  .settings(
    name := "colibri-root",

    skip in publish := true,
  )
  .aggregate(colibri, monix, rx, router)
