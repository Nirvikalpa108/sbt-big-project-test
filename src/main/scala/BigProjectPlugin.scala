// Copyright (C) 2015 Sam Halliday
// License: Apache-2.0
package fommil

import sbt._
import Keys._

object BigProjectKeys {
  /**
   * WORKAROUND: artifactPath results in expensive project scan:
   * {{{
   *   val jar = (artifactPath in Compile in packageBin).value
   * }}}
   */
  val packageBinFile = TaskKey[File](
    "package-bin-file",
    "Cheap way to obtain the location of the packageBin file."
  )

  /**
   * WORKAROUND: https://bugs.eclipse.org/bugs/show_bug.cgi?id=224708
   *
   * Teams that use Eclipse often put tests in separate packages.
   */
  val eclipseTestsFor = SettingKey[Option[ProjectReference]](
    "eclipse-tests-for",
    "When defined, points to the project that this project is testing."
  )

}

object BigProjectPlugin extends AutoPlugin {
  val autoImports = BigProjectKeys

  import autoImports._

  def packageBinFileTask(phase: Configuration) =
    (projectID, crossTarget, scalaBinaryVersion).map { (module, dir, scala) =>
      val append = phase match {
        case Compile => ""
        case Test    => "-tests"
        case _       => "-" + phase.name
      }
      dir / s"${module.name}_${scala}-${module.revision}$append.jar"
    }

  /**
   * WORKAROUND https://github.com/sbt/sbt/issues/2270
   *
   * Stock sbt will rescan all a project's dependency's before running
   * any task, such as `compile`. This can be prohibitive (taking 1+
   * minutes) for top-level projects in large structures.
   *
   * This reimplements packageTask to only run Package if the jar
   * doesn't exist, which limits the scope of sbt's classpath scans.
   */
  def dynamicPackageBinTask(phase: Configuration): Def.Initialize[Task[File]] = Def.taskDyn {
    // all references to `.value` in a Task mean that the task is
    // aggressively invoked as a dependency to this task. However, it
    // is possible to lazily call dependent tasks from Dynamic Tasks.
    // http://www.scala-sbt.org/0.13/docs/Tasks.html#Dynamic+Computations+with
    val jar = (packageBinFile in phase).value
    if (jar.exists()) Def.task {
      jar
    }
    else Def.task {
      val s = (streams in packageBin in phase).value
      val config = (packageConfiguration in packageBin in phase).value
      Package(config, s.cacheDirectory, s.log)
      jar
    }
  }

  // FIXME: compile is not stopping at the packageBin above, stunt further...
  val compileCache = new java.util.concurrent.ConcurrentHashMap[String, inc.Analysis]()
  def dynamicCompileTask: Def.Initialize[Task[inc.Analysis]] = Def.taskDyn {
    val key = s"${thisProject.value.id}/${configuration.value}"
    val cached = compileCache.get(key)

    if (cached == null) Def.task {
      streams.value.log.info(s"CALCULATING $key")
      val calculated = Defaults.compileTask.value
      compileCache.put(key, calculated)
      calculated
    }
    else Def.task {
      streams.value.log.info(s"CACHE HIT $key")
      cached
    }
  }

  /**
   * The default behaviour of `compile` leaves the `packageBin`
   * untouched, but we'd like to delete the `packageBin` on all
   * `compile`s to avoid staleness.
   */
  def deleteBinOnCompileTask(phase: Configuration) =
    (packageBinFile in phase, compile in phase, streams in phase) map { (bin, orig, s) =>
      if (bin.exists()) {
        s.log.info(s"deleting prior to compile $bin")
        bin.delete()
      }
      orig
    }

  /**
   * Ensures that Eclipse-style tests always rebuild their main
   * project first. This incurs the cost of rebuilding the packageBin
   * on each test invocation but ensures the expected semantics are
   * observed.
   *
   * This is attached as a post-update phase since its awkward to
   * attach a Task as a pre-step to compile.
   *
   * There is the potential to optimise this further by calling
   * `Package` instead of blindly deleting.
   */
  def eclipseTestVisibilityTask(phase: Configuration) = Def.taskDyn {
    val dep = eclipseTestsFor.value
    val orig = (update in phase).value
    dep match {
      case Some(ref) if phase.extendsConfigs.contains(Test) => Def.task {
        // limitation: only deletes the current and referenced main jars
        // val main = (packageBinFile in Compile).value
        // if (main.exists()) main.delete()
        val s = (streams in Compile in ref).value
        val upstream = (packageBinFile in Compile in ref).value
        s.log.info(s"checking prior to test $upstream")

        if (upstream.exists()) {
          s.log.info(s"deleting prior to test $upstream")
          upstream.delete()
        }
        orig
      }
      case _ => Def.task { orig }
    }
  }

  /**
   * It is not possible to replace existing Tasks in
   * `projectSettings`, so users have to manually add these to each of
   * their projects.
   */
  def overrideProjectSettings(phase: Configuration): Seq[Setting[_]] = Seq(
    // inConfig didn't work, so pass Configuration explicitly
    packageBin in phase := dynamicPackageBinTask(phase).value,
    //update in phase := eclipseTestVisibilityTask(phase).value,
    //compile in phase := deleteBinOnCompileTask(phase).value,
    packageBinFile in phase := packageBinFileTask(phase).value
  ) ++ inConfig(phase)(
      // FIXME: move everything to be inConfig defined from the outside
      compile := dynamicCompileTask.value
    )

  override val projectSettings: Seq[Setting[_]] = Seq(
    exportJars := true,
    eclipseTestsFor := None
  )

}
