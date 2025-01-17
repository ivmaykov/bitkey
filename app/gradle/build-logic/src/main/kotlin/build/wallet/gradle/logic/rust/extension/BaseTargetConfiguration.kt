package build.wallet.gradle.logic.rust.extension

import build.wallet.gradle.logic.rust.task.BaseCompileRustTask
import build.wallet.gradle.logic.rust.task.GenerateKotlinRustBindingsTask
import build.wallet.gradle.logic.rust.util.RustCompilationProfile
import build.wallet.gradle.logic.rust.util.RustTarget
import build.wallet.gradle.logic.rust.util.rustBinOutputDirectory
import build.wallet.gradle.logic.rust.util.rustBuildDirectory
import build.wallet.gradle.logic.rust.util.rustUniffiOutputDirectory
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import javax.inject.Inject

abstract class BaseTargetConfiguration
  @Inject
  constructor(
    protected val project: Project,
    protected val extension: KotlinMultiplatformRustExtension,
  ) {
    private val addedTargets = mutableSetOf<RustTarget>()

    private val compileRustTaskCache =
      mutableMapOf<Pair<RustTarget, RustCompilationProfile>, TaskProvider<out BaseCompileRustTask>>()

    protected abstract val kmpTargetName: String

    internal abstract val supportedProfiles: List<RustCompilationProfile>

    internal abstract val compileRustTaskClass: Class<out BaseCompileRustTask>

    internal fun configureTarget(target: RustTarget) {
      if (target in addedTargets) {
        return
      }

      if (addedTargets.isEmpty()) {
        onFirstTarget()

        registerAndConfigureGenerateKotlinRustBindingsTask(target)
      }

      addedTargets.add(target)

      supportedProfiles.forEach {
        getOrCreateCompileRustTask(target, it)
      }
    }

    private fun getOrCreateCompileRustTask(
      target: RustTarget,
      profile: RustCompilationProfile,
    ): TaskProvider<out BaseCompileRustTask> =
      compileRustTaskCache.getOrPut(target to profile) {
        registerAndConfigureCompileRustTask(target, profile)
      }

    private fun registerAndConfigureCompileRustTask(
      target: RustTarget,
      profile: RustCompilationProfile,
    ): TaskProvider<out BaseCompileRustTask> =
      project.tasks.register(
        "compileRust".withFlavorName(target, profile),
        compileRustTaskClass
      ) {
        inputs.files(extension.rustProjectFiles)
        this.target.set(target)
        this.profile.set(profile)
        targetDirectory.set(project.rustBuildDirectory.map { it.dir("target") })
        packageName.set(extension.packageName)
        libraryName.set(extension.libraryName)
        outputDirectory.set(
          project.rustBinOutputDirectory.map { it.withFlavorPath(target, profile) }
        )
      }.also {
        configureCompileRustTask(profile, it)
      }

    private fun registerAndConfigureGenerateKotlinRustBindingsTask(
      target: RustTarget,
    ): TaskProvider<GenerateKotlinRustBindingsTask> =
      project.tasks.register(
        "generateKotlinRustBindings" + kmpTargetName.replaceFirstChar { it.uppercase() },
        GenerateKotlinRustBindingsTask::class.java
      ) {
        inputs.files(extension.rustProjectFiles)
        outputDirectory.set(
          project.rustUniffiOutputDirectory.map { directory ->
            directory.dir(kmpTargetName.replaceFirstChar { it.lowercase() })
          }
        )

        val compileRustTask = getOrCreateCompileRustTask(target, RustCompilationProfile.Debug)
        rustLibraryWithDebugSymbols.set(getOutputLibraryFile(compileRustTask.get(), target))
      }.also {
        configureGenerateKotlinRustBindingsTask(it)
      }

    private fun String.withFlavorName(
      target: RustTarget,
      profile: RustCompilationProfile,
    ): String = this + target.flavorName + profile.flavorName

    private fun Directory.withFlavorPath(
      target: RustTarget,
      profile: RustCompilationProfile,
    ): Directory =
      this
        .dir(target.flavorName)
        .dir(profile.outputDirectoryName)

    protected abstract fun onFirstTarget()

    internal abstract fun configureCompileRustTask(
      profile: RustCompilationProfile,
      task: TaskProvider<out BaseCompileRustTask>,
    )

    internal abstract fun configureGenerateKotlinRustBindingsTask(
      task: TaskProvider<GenerateKotlinRustBindingsTask>,
    )

    internal abstract fun getOutputLibraryFile(
      compileRustTask: BaseCompileRustTask,
      target: RustTarget,
    ): Provider<RegularFile>
  }
