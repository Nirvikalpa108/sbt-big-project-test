// Copyright (C) 2015 Sam Halliday
// License: Apache-2.0

import sbt._
import Keys._
import Def.Initialize

import fommil.BigProjectPlugin
import fommil.BigProjectKeys

import fommil.BigProjectTestSupport

/**
 * A simple linear multi-module project of the form
 *
 *   A <- B <- C <- D
 *
 * where D is an Eclipse-style test project for C.
 */
object SimpleBuild extends Build {

  override lazy val settings = super.settings ++ Seq(
    scalaVersion := "2.10.6",
    version := "v1",

    // forces single threaded Tasks for easy debugging
    concurrentRestrictions in Global := Seq(Tags.limitAll(1))
  )

  def simpleProject(name: String): Project = {
    val proj = Project(name, file(name)).enablePlugins(BigProjectPlugin).settings(
      BigProjectPlugin.overrideProjectSettings(Compile, Test)
    ).settings (
      BigProjectTestSupport.testInstrumentation(Compile, Test)
    )
    BigProjectTestSupport.createSources(proj.id)
    proj
  }

  val a = simpleProject("a")
  val b = simpleProject("b") dependsOn(a)
  val c = simpleProject("c") dependsOn(b)
  val d = simpleProject("d") dependsOn(c)


}
