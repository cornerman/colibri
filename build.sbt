inThisBuild(Seq(
  organization := "com.github.cornerman",

  scalaVersion := "2.12.15",

  crossScalaVersions := Seq("2.12.15", "2.13.6"),

  licenses := Seq("MIT License" -> url("https://opensource.org/licenses/MIT")),

  homepage := Some(url("https://github.com/cornerman/colibri")),

  scmInfo := Some(ScmInfo(
    url("https://github.com/cornerman/colibri"),
    "scm:git:git@github.com:cornerman/colibri.git",
    Some("scm:git:git@github.com:cornerman/colibri.git"))
  )
))

lazy val commonSettings = Seq(
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),

  libraryDependencies ++= Seq(
    "org.scalatest" %%% "scalatest" % "3.2.10" % Test,
  ),

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
    val githubRepo    = "cornerman/colibri"
    val local         = baseDirectory.value.toURI
    val subProjectDir = baseDirectory.value.getName
    val remote        = s"https://raw.githubusercontent.com/${githubRepo}/${git.gitHeadCommit.value.get}"
    s"-P:scalajs:mapSourceURI:$local->$remote/${subProjectDir}/"
  },
)

lazy val colibri = project
  .enablePlugins(ScalaJSPlugin)
  .in(file("colibri"))
  .settings(commonSettings, jsSettings)
  .settings(
    name := "colibri",

    libraryDependencies ++= Seq(
      "org.scala-js"  %%% "scalajs-dom" % "2.0.0",
      "org.typelevel" %%% "cats-core" % "2.6.1",
      "org.typelevel" %%% "cats-effect" % "2.5.4",
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
