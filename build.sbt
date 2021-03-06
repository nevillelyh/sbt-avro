// plugin version
ThisBuild / dynverSonatypeSnapshots := true
ThisBuild / version := {
  val orig = (ThisBuild / version).value
  if (orig.endsWith("-SNAPSHOT")) "3.1.1-SNAPSHOT"
  else orig
}

// sbt-github-actions
ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(name = Some("Build project"), commands = List("compile", "test", "scripted"))
)
ThisBuild / githubWorkflowTargetBranches := Seq("master")
ThisBuild / githubWorkflowJavaVersions := Seq("1.8", "1.11")
ThisBuild / githubWorkflowPublishTargetBranches := Seq(RefPredicate.StartsWith(Ref.Tag("v")))
ThisBuild / githubWorkflowPublish := Seq(WorkflowStep.Sbt(List("ci-release")))

lazy val `sbt-avro`: Project = project
    .in(file("."))
    .enablePlugins(SbtPlugin)
    .settings(
      organization := "com.cavorite",
      description := "Sbt plugin for compiling Avro sources",
      homepage := Some(url("https://github.com/sbt/sbt-avro")),
      pluginCrossBuild / sbtVersion := "1.2.8",
      Compile / scalacOptions ++= Seq("-deprecation"),
      libraryDependencies ++= Seq(
        Dependencies.Provided.AvroCompiler,
        Dependencies.Test.Spec2
      ),
      licenses += ("BSD 3-Clause", url("https://github.com/sbt/sbt-avro/blob/master/LICENSE")),
      publishTo := (bintray / publishTo).value,
      publishMavenStyle := false,
      bintrayOrganization := Some("sbt"),
      bintrayRepository := "sbt-plugin-releases",
      bintrayPackage := "sbt-avro2",
      scriptedLaunchOpts ++= Seq(
        "-Xmx1024M",
        "-Dplugin.version=" + version.value,
      ),
      scriptedBufferLog := false,
    )
