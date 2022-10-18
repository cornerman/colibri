Global / onChangedBuildSource := ReloadOnSourceChanges

// Workaround for https://github.com/sbt/sbt/issues/3465
crossScalaVersions := Nil

inThisBuild(
  Seq(
    organization := "com.github.cornerman",
    licenses     := Seq("MIT License" -> url("https://opensource.org/licenses/MIT")),
    homepage     := Some(url("https://github.com/cornerman/colibri")),
    scmInfo      := Some(
      ScmInfo(
        url("https://github.com/cornerman/colibri"),
        "scm:git:git@github.com:cornerman/colibri.git",
        Some("scm:git:git@github.com:cornerman/colibri.git"),
      ),
    ),
    pomExtra     :=
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
  crossScalaVersions := Seq("2.12.17", "2.13.10", "3.2.0"),
  scalaVersion       := "2.13.10",
  libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) => Seq.empty
    case _            =>
      Seq(
        compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full),
        "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided,
      )
  }),
  libraryDependencies ++= Seq(
    "org.scalatest" %%% "scalatest" % "3.2.14" % Test,
  ),
  /* scalacOptions --= Seq("-Xfatal-warnings"), // overwrite option from https://github.com/DavidGregory084/sbt-tpolecat */
)

lazy val colibri = project
  .enablePlugins(ScalaJSPlugin)
  .in(file("colibri"))
  .settings(commonSettings)
  .settings(
    name := "colibri",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core"   % "2.8.0",
      "org.typelevel" %%% "cats-effect" % "3.3.13",
    ),
  )

lazy val reactive = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(colibri)
  .in(file("reactive"))
  .settings(commonSettings)
  .settings(
    name := "colibri-reactive",
    libraryDependencies ++= Seq(
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
      "org.scala-js" %%% "scalajs-dom" % "2.3.0",
    ),
  )

lazy val jsdomTests = project
  .enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin)
  .in(file("jsdom-tests"))
  .dependsOn(jsdom)
  .settings(commonSettings)
  .settings(
    publish / skip         := true,
    name                   := "colibri-jsdom-tests",
    Test / requireJsDomEnv := true,
    installJsdom / version := "19.0.0",
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

lazy val rx = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(colibri)
  .in(file("rx"))
  .settings(commonSettings)
  .settings(
    name               := "colibri-rx",
    crossScalaVersions := Seq("2.12.17", "2.13.10"), // no scala3, because scala.rx uses scala2 macros
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
      "com.raquo" %%% "airstream" % "0.14.5",
    ),
  )

lazy val zio = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(colibri)
  .in(file("zio"))
  .settings(commonSettings)
  .settings(
    name := "colibri-zio",
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.4.0",
      "dev.zio"           %%% "zio"             % "2.0.2",
      "dev.zio"           %%% "zio-streams"     % "2.0.2",
    ),
  )

lazy val fs2 = project
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(colibri)
  .in(file("fs2"))
  .settings(commonSettings)
  .settings(
    name := "colibri-fs2",
    libraryDependencies ++= Seq(
      "co.fs2" %%% "fs2-core" % "3.2.7",
    ),
  )
