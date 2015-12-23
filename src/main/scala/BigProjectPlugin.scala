// Copyright (C) 2015 Sam Halliday
// License: Apache-2.0
package fommil

import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.apache.ivy.core.module.id.ModuleRevisionId
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

  // FIXME: strategies to invalidate these caches

  /*
   It's definitely one of these that's the entrance point to the subproject, and
   causing transitive task creation joy.

     *:projectDescriptors: 2.856385 ms      // TASK (not critical path)
     compile:exportedProducts: 0.907261 ms  // TASK (on critical path, but maybe not the primary node)
     *:ivyConfiguration: 0.353889 ms        // TASK
     compile:unmanagedJars: 0.323248 ms     // TASK (probably not critical path)
     *:ivySbt: 0.159503 ms                  // TASK (probably not critical path)
     *:bootResolvers: 0.065265 ms           // TASK (probably not critical path)
     *:ivyModule: 0.048709999999999996 ms   // TASK (probably not critical path)
     *:projectDependencies: 0.039265 ms     // TASK (not critical path)
     *:fullResolvers: 0.02361 ms            // TASK (probably not critical path)
     *:allDependencies: 0.019998 ms         // TASK (blows up when cached)
     compile:packageBinFile: 0.016877 ms    // SETTING
     *:externalResolvers: 0.008927 ms       // TASK (probably not critical path)
     *:projectResolver: 0.008799 ms         // TASK (probably not critical path)
     *:moduleSettings: 0.0085 ms            // TASK (probably not critical path)

   it is likely that an aggregate Task in the top-level project is calling these tasks
   on each of the dependents, much like transitiveUpdate.

   */

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

  // also close...
  val projectDescriptorsCache = new java.util.concurrent.ConcurrentHashMap[String, Map[ModuleRevisionId, ModuleDescriptor]]()
  def dynamicProjectdescriptorsTask: Def.Initialize[Task[Map[ModuleRevisionId, ModuleDescriptor]]] = Def.taskDyn {
    val key = s"${thisProject.value.id}/${configuration.value}"
    val jar = packageBinFile.value
    val cached = projectDescriptorsCache.get(key)

    if (jar.exists() && cached != null) Def.task {
      streams.value.log.info(s"PROJECTDESCRIPTORS CACHE HIT $key")
      cached
    }
    else Def.task {
      streams.value.log.info(s"PROJECTDESCRIPTORS CALCULATING $key")
      val calculated = Classpaths.depMap.value
      projectDescriptorsCache.put(key, calculated)
      calculated
    }
  }

  // This is definitely causing big traversals of subprojects.
  // We should definitely cache this for the current project.
  val transitiveUpdateCache = new java.util.concurrent.ConcurrentHashMap[String, Seq[UpdateReport]]()
  def dynamicTransitiveUpdateTask(config: Option[Configuration]): Def.Initialize[Task[Seq[UpdateReport]]] = Def.taskDyn {
    val key = s"${thisProject.value.id}/${config}" // HACK to support no-config
    val cached = transitiveUpdateCache.get(key)

    // note, must be in the dynamic task
    val make = new ScopeFilter.Make {}
    val selectDeps = ScopeFilter(make.inDependencies(ThisProject, includeRoot = false))

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
        transitiveUpdate := dynamicTransitiveUpdateTask(Some(phase)).value,
        exportedProducts := dynamicExportedProductsTask.value,
        projectDescriptors := dynamicProjectdescriptorsTask.value
      )
    ) ++ Seq(
        // intentionally not in a configuration
        transitiveUpdate := dynamicTransitiveUpdateTask(None).value
      )

  override val projectSettings: Seq[Setting[_]] = Seq(
    exportJars := true,
    eclipseTestsFor := None
  )

}
