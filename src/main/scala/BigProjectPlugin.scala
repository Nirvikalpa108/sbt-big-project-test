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

  private def packageBinFileTask(config: Configuration) =
    (projectID, crossTarget, scalaBinaryVersion).map { (module, dir, scala) =>
      val append = config match {
        case Compile => ""
        case Test    => "-tests"
        case _       => "-" + config.name
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
  private def dynamicPackageBinTask(config: Configuration): Def.Initialize[Task[File]] = Def.taskDyn {
    // all references to `.value` in a Task mean that the task is
    // aggressively invoked as a dependency to this task. However, it
    // is possible to lazily call dependent tasks from Dynamic Tasks.
    // http://www.scala-sbt.org/0.13/docs/Tasks.html#Dynamic+Computations+with
    val jar = (packageBinFile in config).value
    if (jar.exists()) Def.task {
      jar
    }
    else Def.task {
      val s = (streams in packageBin in config).value
      val c = (packageConfiguration in packageBin in config).value
      Package(c, s.cacheDirectory, s.log)
      jar
    }
  }

  // original causes traversals of dependency projects
  private val transitiveUpdateCache = new java.util.concurrent.ConcurrentHashMap[String, Seq[UpdateReport]]()
  private def dynamicTransitiveUpdateTask: Def.Initialize[Task[Seq[UpdateReport]]] = Def.taskDyn {
    // doesn't have a Configuration, always project-level
    val key = s"${thisProject.value.id}"
    val cached = transitiveUpdateCache.get(key)

    // note, must be in the dynamic task
    val make = new ScopeFilter.Make {}
    val selectDeps = ScopeFilter(make.inDependencies(ThisProject, includeRoot = false))

    if (cached != null) Def.task {
      streams.value.log.debug(s"TRANSITIVEUPDATE CACHE HIT $key")
      cached
    }
    else Def.task {
      streams.value.log.debug(s"TRANSITIVEUPDATE CALCULATING $key")
      val allUpdates = update.?.all(selectDeps).value
      val calculated = allUpdates.flatten ++ globalPluginUpdate.?.value
      transitiveUpdateCache.put(key, calculated)
      calculated
    }
  }

  // original causes traversals of dependency projects
  private val dependencyClasspathCache = new java.util.concurrent.ConcurrentHashMap[String, Classpath]()
  private def dynamicDependencyClasspathTask: Def.Initialize[Task[Classpath]] = Def.taskDyn {
    val key = s"${thisProject.value.id}/${configuration.value}"
    val cached = dependencyClasspathCache.get(key)
    if (cached != null) Def.task {
      streams.value.log.debug(s"DEPENDENCIESCLASSPATH CACHE HIT $key")
      cached
    }
    else Def.task {
      streams.value.log.debug(s"DEPENDENCIESCLASSPATH CALCULATING $key")
      val calculated = internalDependencyClasspath.value ++ externalDependencyClasspath.value
      dependencyClasspathCache.put(key, calculated)
      calculated
    }
  }

  // original causes traversals of dependency projects
  private val projectDescriptorsCache = new java.util.concurrent.ConcurrentHashMap[String, Map[ModuleRevisionId, ModuleDescriptor]]()
  private def dynamicProjectdescriptorsTask: Def.Initialize[Task[Map[ModuleRevisionId, ModuleDescriptor]]] = Def.taskDyn {
    // doesn't have a Configuration, always project-level
    val key = s"${thisProject.value.id}"
    val cached = projectDescriptorsCache.get(key)

    if (cached != null) Def.task {
      streams.value.log.debug(s"PROJECTDESCRIPTORS CACHE HIT $key")
      cached
    }
    else Def.task {
      streams.value.log.debug(s"PROJECTDESCRIPTORS CALCULATING $key")
      val calculated = Classpaths.depMap.value
      projectDescriptorsCache.put(key, calculated)
      calculated
    }
  }

  /**
   * The default behaviour of `compile` leaves the `packageBin`
   * untouched, but we'd like to delete the `packageBin` on all
   * `compile`s to avoid staleness.
   */
  private def deleteBinOnCompileTask(config: Configuration) =
    (packageBinFile in config, compile in config, streams in config) map { (bin, orig, s) =>
      if (bin.exists()) {
        s.log.debug(s"deleting prior to compile $bin")
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
   * This is attached as a post-update config since its awkward to
   * attach a Task as a pre-step to compile.
   *
   * There is the potential to optimise this further by calling
   * `Package` instead of blindly deleting.
   */
  private def eclipseTestVisibilityTask(config: Configuration) = Def.taskDyn {
    val dep = eclipseTestsFor.value
    val orig = (update in config).value
    dep match {
      case Some(ref) if config.extendsConfigs.contains(Test) => Def.task {
        // limitation: only deletes the current and referenced main jars
        // val main = (packageBinFile in Compile).value
        // if (main.exists()) main.delete()
        val s = (streams in Compile in ref).value
        val upstream = (packageBinFile in Compile in ref).value
        s.log.debug(s"checking prior to test $upstream")

        if (upstream.exists()) {
          s.log.debug(s"deleting prior to test $upstream")
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
  def overrideProjectSettings(configs: Configuration*): Seq[Setting[_]] = Seq(
    // project level, no configuration
    exportJars := true,
    transitiveUpdate := dynamicTransitiveUpdateTask.value,
    projectDescriptors := dynamicProjectdescriptorsTask.value
  ) ++ configs.flatMap { config =>
      inConfig(config)(
        dependencyClasspath := dynamicDependencyClasspathTask.value
      ) ++ Seq(
          // inConfig didn't work, so pass Configuration explicitly
          packageBin in config := dynamicPackageBinTask(config).value,
          //update in config := eclipseTestVisibilityTask(config).value,
          //compile in config := deleteBinOnCompileTask(config).value,
          packageBinFile in config := packageBinFileTask(config).value
        )
    }

}
