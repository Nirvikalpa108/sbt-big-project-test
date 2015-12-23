// Copyright (C) 2015 Sam Halliday
// License: Apache-2.0
package fommil

import sbt._
import Keys._

/**
 * Instrumentation and test assertions that can be used by downstream
 * projects (especially our sbt-tests) to validate the plugin's
 * behaviour.
 */
object BigProjectTestSupport {
  ///////////////////////////////////////////////////////////////////////////
  // Test instrumentation to detect invocations of expensive Tasks
  // WORKAROUND https://github.com/sbt/sbt/issues/1209
  val testConfigInstrumentation = Seq(
    // TODO: breadcrumbs should pass Some(Configuration)
    update <<= update dependsOn breadcrumb("update"),
    compile <<= compile dependsOn breadcrumb("compile"),
    packageBin <<= packageBin dependsOn breadcrumb("packageBin")
  )
  val testInstrumentation = Seq(
    projectDependencies <<= projectDependencies dependsOn breadcrumb("projectDependencies"),
    projectDescriptors <<= projectDescriptors dependsOn breadcrumb("projectDescriptors")
  )

  def breadcrumb(name: String, config: Option[Configuration] = None) = baseDirectory.map { dir => IO.touch(dir / (config.map(_ + "-").getOrElse("") + name + ".breadcrumb")) }

  ///////////////////////////////////////////////////////////////////////////
  // `scripted` Tests
  /** Finds all breadcrumbs in the current project. */
  val breadcrumbs = taskKey[Set[File]]("All breadcrumbs.")
  val breadcrumbsTask: Def.Initialize[Task[Set[File]]] = Def.task {
    val dir = baseDirectory.value
    dir.tree.filter(_.getName.endsWith(".breadcrumb")).toSet
  }

  /** Delete all breadcrumbs in the current project. */
  val breadcrumbsClear = taskKey[Unit]("Clear all breadcrumbs.")
  val breadcrumbsClearTask: Def.Initialize[Task[Unit]] = Def.task {
    val crumbs = breadcrumbs.value
    crumbs.foreach { crumb =>
      if (crumb.exists()) crumb.delete()
    }
  }

  val breadcrumbsExpect = inputKey[Unit]("Check that only these breadcrumbs exist.")

  val scriptedTasks = Seq(
    breadcrumbs := breadcrumbsTask.value,
    breadcrumbsClear := breadcrumbsClearTask.value,
    breadcrumbsExpect := {
      // I don't know how to define this as a val
      // Def.inputTask gives runtime errors
      val expect = parser.parsed.map(_ + ".breadcrumb").toSet
      val base = baseDirectory.value
      val crumbs = breadcrumbs.value
      val got = crumbs.flatMap { crumb => IO.relativize(base, crumb) }
      if (expect != got)
        throw new MessageOnlyException(
          s"expected ${expect.size} breadcrumbs in $base but got ${got.size}: $got"
        )
    }
  )

  ///////////////////////////////////////////////////////////////////////////
  // Utils
  lazy val parser = complete.Parsers.spaceDelimited("<arg>")
  implicit class RichFile(val file: File) extends AnyVal {
    /**
     * @return the file and its descendent family tree (if it is a directory).
     */
    def tree: Stream[File] = if (!file.isDirectory()) Stream(file) else {
      import collection.JavaConversions._
      val these = file.listFiles.toStream
      these #::: these.filter(_.isDirectory).flatMap(_.tree)
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // creates some sources for the named Project
  def createSources(name: String): Unit = {
    val sanitised = name.replace("-", "")

    val dir = file(name) / "src/main/scala/"
    dir.mkdirs()
    val foo = dir / sanitised / "Foo.scala"
    val bar = dir / sanitised / "Bar.scala"

    // having two main classes is a good way to eyeball when expensive
    // classpath scanning is taking place because it'll always
    // complain about multiple mains.
    IO.write(foo, s"package $sanitised\nobject Foo extends App")
    IO.write(bar, s"package $sanitised\nobject Bar extends App")

    val testdir = file(name) / "src/test/scala/"
    testdir.mkdirs()
    val testfoo = testdir / sanitised / "FooTest.scala"

    // introducing ScalaTest would just slow things further...
    IO.write(testfoo, s"package $sanitised\nclass FooTest")
  }
}
