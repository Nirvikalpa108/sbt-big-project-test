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

  // FIXME: invalidate this cache with source file watchers or if user requests directly
  // FIXME: compile is not stopping at the packageBin above, stunt further...
  /*
   It's definitely one of these that's the entrance point to the subproject...

  c//:update: 15.225537 ms
  c//:projectDescriptors: 3.7556369999999997 ms
  c/compile:compile: 0.6808299999999999 ms
  c/compile:unmanagedJars: 0.5502589999999999 ms
  c//:dependencyPositions: 0.37309 ms
  c//:ivyConfiguration: 0.346205 ms
  c//:ivySbt: 0.345838 ms
  c/compile:exportedProducts: 0.212473 ms
  c/compile:discoveredMainClasses: 0.20038799999999998 ms
  c//:bootResolvers: 0.09755499999999999 ms
  c/compile:packageBin: 0.088985 ms
  c//:dependencyCacheDirectory: 0.082843 ms
  c//:ivyModule: 0.069594 ms
  c//:fullResolvers: 0.054268 ms
  c//:transitiveUpdate: 0.033884 ms
  c//:projectDependencies: 0.029869 ms
  c//:allDependencies: 0.024919999999999998 ms
  c//:credentials: 0.014404 ms
  c/compile:packageBinFile: 0.014378 ms
  c//:updateCacheName: 0.010686 ms
  c//:externalResolvers: 0.007783999999999999 ms
  c//:projectResolver: 0.007357 ms
  c//:moduleSettings: 0.006816999999999999 ms
  c//:update::unresolvedWarningConfiguration: 0.002446 ms

   */

  // not a critical node
  val compileCache = new java.util.concurrent.ConcurrentHashMap[String, inc.Analysis]()
  def dynamicCompileTask: Def.Initialize[Task[inc.Analysis]] = Def.taskDyn {
    val key = s"${thisProject.value.id}/${configuration.value}"
    val jar = packageBinFile.value
    val cached = compileCache.get(key)

    if (jar.exists() && cached != null) Def.task {
      streams.value.log.info(s"COMPILE CACHE HIT $key")
      cached
    }
    else Def.task {
      streams.value.log.info(s"COMPILE CALCULATING $key")
      val calculated = Defaults.compileTask.value
      compileCache.put(key, calculated)
      calculated
    }
  }

  // not a critical node
  val updateCache = new java.util.concurrent.ConcurrentHashMap[String, UpdateReport]()
  def dynamicUpdateTask(config: Option[Configuration]): Def.Initialize[Task[UpdateReport]] = Def.taskDyn {
    val key = s"${thisProject.value.id}/${config}" // HACK to support no-config
    val jar = (packageBinFile in Compile).value // HACK or should be defined without Compile
    val cached = updateCache.get(key)

    if (jar.exists() && cached != null) Def.task {
      streams.value.log.info(s"UPDATE CACHE HIT $key")
      cached
    }
    else Def.task {
      streams.value.log.info(s"UPDATE CALCULATING $key")
      val calculated = (Classpaths.updateTask tag (Tags.Update, Tags.Network)).value
      ConflictWarning(conflictWarning.value, calculated, streams.value.log)
      updateCache.put(key, calculated)
      calculated
    }
  }

  // not a critical node, but close!
  val exportedProductsCache = new java.util.concurrent.ConcurrentHashMap[String, Classpath]()
  def dynamicExportedProductsTask: Def.Initialize[Task[Classpath]] = Def.taskDyn {
    val key = s"${thisProject.value.id}/${configuration.value}"
    val jar = packageBinFile.value
    val cached = exportedProductsCache.get(key)

    if (jar.exists() && cached != null) Def.task {
      streams.value.log.info(s"EXPORTEDPRODUCTS CACHE HIT $key")
      cached
    }
    else Def.task {
      streams.value.log.info(s"EXPORTEDPRODUCTS CALCULATING $key")
      val calculated = (Classpaths.exportProductsTask).value
      exportedProductsCache.put(key, calculated)
      calculated
    }
  }

  // This is definitely causing big traversals of subprojects. We should definitely cache this for the current project.
  val transitiveUpdateCache = new java.util.concurrent.ConcurrentHashMap[String, Seq[UpdateReport]]()
  def dynamicTransitiveUpdateTask(config: Option[Configuration]): Def.Initialize[Task[Seq[UpdateReport]]] = Def.taskDyn {
    val key = s"${thisProject.value.id}/${config}" // HACK to support no-config
    val jar = (packageBinFile in Compile).value // HACK or should be defined without Compile
    val cached = transitiveUpdateCache.get(key)

    // why is the instance private?
    // note, must be in the dynamic task
    val make = new ScopeFilter.Make {}
    // FIXME: change this to only return the first layer, not the whole list
    val selectDeps = ScopeFilter(make.inDependencies(ThisProject, includeRoot = false))

    //if (jar.exists() && cached != null) Def.task {
    if (cached != null) Def.task {
      streams.value.log.info(s"TRANSITIVEUPDATE CACHE HIT $key")
      cached
    }
    else Def.task {
      streams.value.log.info(s"TRANSITIVEUPDATE CALCULATING $key")
      val allUpdates = update.?.all(selectDeps).value
      val calculated = allUpdates.flatten ++ globalPluginUpdate.?.value

      transitiveUpdateCache.put(key, calculated)
      calculated
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
      Seq(
        // FIXME: move everything to be inConfig defined from the outside
        compile := dynamicCompileTask.value,
        update := dynamicUpdateTask(Some(phase)).value,
        transitiveUpdate := dynamicTransitiveUpdateTask(Some(phase)).value,
        exportedProducts := dynamicExportedProductsTask.value
      )
    ) ++ Seq(
        // intentionally not in a configuration
        update := dynamicUpdateTask(None).value,
        transitiveUpdate := dynamicTransitiveUpdateTask(None).value
      )

  override val projectSettings: Seq[Setting[_]] = Seq(
    exportJars := true,
    eclipseTestsFor := None
  )

}
