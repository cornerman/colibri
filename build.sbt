// Workaround for https://github.com/sbt/sbt/issues/3465
crossScalaVersions := Nil

inThisBuild(
  Seq(
    organization := "com.github.cornerman",
    licenses := Seq("MIT License" -> url("https://opensource.org/licenses/MIT")),
    homepage := Some(url("https://github.com/cornerman/colibri")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/cornerman/colibri"),
        "scm:git:git@github.com:cornerman/colibri.git",
        Some("scm:git:git@github.com:cornerman/colibri.git"),
      ),
    ),
    pomExtra :=
      <developers>
        <developer>
        <id>jk</id>
        <name>Johannes Karoff</name>
        <url>https://github.com/cornerman</url>
        </developer>
    </developers>,
  ),
)

lazy val commonSettings = Seq(
  crossScalaVersions := Seq("2.12.15", "2.13.8", "3.1.1"),
  scalaVersion := "2.13.8",
  libraryDependencies ++= Seq(
    "org.scalatest" %%% "scalatest" % "3.2.11" % Test,
  ),
)

lazy val colibri = project
  .enablePlugins(ScalaJSPlugin)
  .in(file("colibri"))
  .settings(commonSettings)
  .settings(
    name := "colibri",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core"   % "2.7.0",
      "org.typelevel" %%% "cats-effect" % "3.3.6",
    ),
  )

lazy val jsdom = project
  .enablePlugins(ScalaJSPlugin)
  .in(file("jsdom"))
  .dependsOn(colibri)
  .settings(commonSettings)
  .settings(
    name := "colibri-jsdom",
    libraryDependencies ++= Seq(
      "org.scala-js"  %%% "scalajs-dom" % "2.1.0",
    ),
  )

lazy val router = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(jsdom)
  .in(file("router"))
  .settings(commonSettings)
  .settings(
    name := "colibri-router",
    libraryDependencies ++= Seq(
    ),
  )

lazy val monix = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(colibri)
  .in(file("monix"))
  .settings(commonSettings)
  .settings(
    name := "colibri-monix",
    libraryDependencies ++= Seq(
      "io.monix" %%% "monix" % "3.4.0",
    ),
  )

lazy val rx = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(colibri)
  .in(file("rx"))
  .settings(commonSettings)
  .settings(
    name := "colibri-rx",
    crossScalaVersions := Seq("2.12.15", "2.13.8"), // no scala3, because scala.rx uses scala2 macros
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "scalarx" % "0.4.3",
    ),
  )

lazy val airstream = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(colibri)
  .in(file("airstream"))
  .settings(commonSettings)
  .settings(
    name := "colibri-airstream",
    libraryDependencies ++= Seq(
      "com.raquo" %%% "airstream" % "0.14.2"
    ),
  )
