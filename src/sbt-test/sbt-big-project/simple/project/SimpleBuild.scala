// Copyright (C) 2015 Sam Halliday
// License: Apache-2.0

import sbt._
import Keys._
import Def.Initialize

import fommil.BigProjectPlugin
import fommil.BigProjectKeys

/**
 * A simple linear multi-module project of the form
 *
 *   A <- B <- C <- D
 *
 * where D is an Eclipse-style test project for C.
 */
object SimpleBuild extends Build {

  import fommil.BigProjectTestSupport._

  override lazy val settings = super.settings ++ Seq(
    scalaVersion := "2.10.6",
    version := "v1"
  )

  def simpleProject(name: String): Project = {
    val proj = Project(name, file(name)).enablePlugins(BigProjectPlugin).settings(
      BigProjectPlugin.overrideProjectSettings(Compile),
      BigProjectPlugin.overrideProjectSettings(Test),
      inConfig(Compile)(testConfigInstrumentation),
      inConfig(Test)(testConfigInstrumentation),
      testInstrumentation,
      scriptedTasks
    )
    createSources(proj.id)
    proj
  }

  val a = simpleProject("a")
  val b = simpleProject("b") dependsOn(a)
  val c = simpleProject("c") dependsOn(b)
  val d = simpleProject("d") dependsOn(c)


}
