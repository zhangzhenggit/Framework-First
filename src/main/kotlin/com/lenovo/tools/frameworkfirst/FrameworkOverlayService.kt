package com.lenovo.tools.frameworkfirst

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.psi.PsiManager
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import com.intellij.openapi.util.Disposer

@Service(Service.Level.PROJECT)
class FrameworkOverlayService(private val project: Project) {
    private val logger = Logger.getInstance(FrameworkOverlayService::class.java)
    private val stateLock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var runningRequestKey: String? = null
    private var lastAppliedRequestKey: String? = null
    private var rescheduleRequested: Boolean = false

    fun isProjectEnabled(): Boolean {
        return project.getService(FrameworkProjectStateService::class.java).isEnabled()
    }

    fun addListener(parentDisposable: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parentDisposable) {
            listeners.remove(listener)
        }
    }

    fun setProjectEnabled(enabled: Boolean) {
        val stateService = project.getService(FrameworkProjectStateService::class.java)
        if (stateService.isEnabled() == enabled) {
            return
        }

        stateService.setEnabled(enabled)
        if (enabled) {
            val config = FrameworkOverlayConfigLoader.load(project)
            if (config.frameworkJar == null) {
                FrameworkNotifier.warning(project, "Framework-First skipped: framework.jar not found")
                return
            }
            syncOverlay("manual-enable")
            return
        }

        disableOverlay()
    }

    fun syncOverlay(trigger: String) {
        val request = buildRequest(trigger) ?: return
        if (!beginRequest(request.key)) {
            return
        }

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Preparing Framework-First overlay", false) {
                private var preparedRequest: PreparedRequest? = null
                private var failure: Throwable? = null

                override fun run(indicator: ProgressIndicator) {
                    preparedRequest = runCatching {
                        prepareRequest(request, indicator)
                    }.getOrElse { throwable ->
                        failure = throwable
                        null
                    }
                }

                override fun onSuccess() {
                    val error = failure
                    val prepared = preparedRequest
                    if (project.isDisposed) {
                        finishRequest(request.key, applied = false)
                        return
                    }
                    if (error != null || prepared == null) {
                        val throwable = error ?: IllegalStateException("Framework-First prepare returned null")
                        logger.warn("Framework-First prepare failed for ${request.trigger}", throwable)
                        FrameworkNotifier.warning(
                            project,
                            "Framework-First skipped: ${throwable.message ?: throwable.javaClass.simpleName}",
                        )
                        if (request.viewPreference == FrameworkViewPreference.FRAMEWORK_FIRST) {
                            disableOverlay()
                        }
                        finishRequest(request.key, applied = false)
                        return
                    }

                    applyPreparedRequest(prepared)
                }

                override fun onThrowable(error: Throwable) {
                    if (!project.isDisposed) {
                        logger.warn("Framework-First background task failed for ${request.trigger}", error)
                        FrameworkNotifier.warning(
                            project,
                            "Framework-First skipped: ${error.message ?: error.javaClass.simpleName}",
                        )
                        if (request.viewPreference == FrameworkViewPreference.FRAMEWORK_FIRST) {
                            disableOverlay()
                        }
                    }
                    finishRequest(request.key, applied = false)
                }
            },
        )
    }

    private fun buildRequest(trigger: String): OverlayRequest? {
        if (project.isDisposed) {
            return null
        }

        val stateService = project.getService(FrameworkProjectStateService::class.java)
        if (!stateService.isEnabled()) {
            return null
        }

        val config = FrameworkOverlayConfigLoader.load(project)
        val frameworkJar = config.frameworkJar
        if (frameworkJar == null || !Files.isRegularFile(frameworkJar)) {
            return null
        }

        val modules = targetModules()
        if (modules.isEmpty()) {
            return null
        }

        val sdkOverlayService = project.getService(FrameworkSdkOverlayService::class.java)
        val moduleTargets = modules.mapNotNull { module ->
            val currentSdk = ModuleRootManager.getInstance(module).sdk ?: return@mapNotNull null
            ModuleTarget(
                module = module,
                currentSdk = currentSdk,
                baseSdk = sdkOverlayService.resolveBaseSdk(currentSdk),
            )
        }
        if (moduleTargets.isEmpty()) {
            val message = "Skipped on $trigger: Android modules have no SDK"
            logger.warn(message)
            FrameworkNotifier.warning(project, message)
            return null
        }

        val requestKey = buildRequestKey(frameworkJar, config.viewPreference, moduleTargets)
        if (isAlreadyApplied(requestKey, config.viewPreference, moduleTargets, sdkOverlayService)) {
            return null
        }

        return OverlayRequest(
            trigger = trigger,
            key = requestKey,
            frameworkJar = frameworkJar,
            viewPreference = config.viewPreference,
            moduleTargets = moduleTargets,
        )
    }

    private fun buildRequestKey(
        frameworkJar: java.nio.file.Path,
        viewPreference: FrameworkViewPreference,
        moduleTargets: List<ModuleTarget>,
    ): String {
        val jarState = buildString {
            append(frameworkJar)
            append('|')
            append(runCatching { Files.size(frameworkJar) }.getOrDefault(-1L))
            append('|')
            append(runCatching { Files.getLastModifiedTime(frameworkJar).toMillis() }.getOrDefault(-1L))
            append('|')
            append(viewPreference.storageValue)
        }
        val moduleState = moduleTargets
            .sortedBy { it.module.name }
            .joinToString("|") { target ->
                "${target.module.name}:${target.baseSdk.name}:${target.baseSdk.sdkType.name}"
            }
        return "$jarState||$moduleState"
    }

    private fun isAlreadyApplied(
        requestKey: String,
        viewPreference: FrameworkViewPreference,
        moduleTargets: List<ModuleTarget>,
        sdkOverlayService: FrameworkSdkOverlayService,
    ): Boolean {
        synchronized(stateLock) {
            if (requestKey != lastAppliedRequestKey) {
                return false
            }
        }

        return moduleTargets.all { target ->
            sdkOverlayService.isUsableManagedOverlaySdk(target.currentSdk, viewPreference)
        }
    }

    private fun beginRequest(requestKey: String): Boolean {
        synchronized(stateLock) {
            val runningKey = runningRequestKey
            return when {
                runningKey == null -> {
                    runningRequestKey = requestKey
                    true
                }

                runningKey == requestKey -> false
                else -> {
                    rescheduleRequested = true
                    false
                }
            }
        }
    }

    private fun prepareRequest(
        request: OverlayRequest,
        indicator: ProgressIndicator,
    ): PreparedRequest {
        indicator.text = "Preparing Framework-First overlay"
        val sdkOverlayService = project.getService(FrameworkSdkOverlayService::class.java)
        sdkOverlayService.cleanupDiskArtifacts()

        val preparedByKey = LinkedHashMap<String, PreparedResult>()
        val totalTargets = request.moduleTargets.size.coerceAtLeast(1)
        request.moduleTargets.forEachIndexed { index, target ->
            indicator.fraction = index.toDouble() / totalTargets.toDouble()
            indicator.text2 = target.baseSdk.name

            val overlayKey = overlayKey(target.baseSdk, request.viewPreference)
            preparedByKey.getOrPut(overlayKey) {
                runCatching {
                    PreparedResult.Prepared(
                        sdkOverlayService.prepareOverlay(
                            target.baseSdk,
                            request.frameworkJar,
                            request.viewPreference,
                        ),
                    )
                }.getOrElse { throwable ->
                    logger.warn("Failed to prepare overlay SDK for ${target.baseSdk.name}", throwable)
                    PreparedResult.Failed(
                        target.baseSdk.name,
                        throwable.message ?: throwable.javaClass.simpleName,
                    )
                }
            }
        }
        indicator.fraction = 1.0
        return PreparedRequest(request, preparedByKey)
    }

    private fun applyPreparedRequest(preparedRequest: PreparedRequest) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                finishRequest(preparedRequest.request.key, applied = false)
                return@invokeLater
            }
            if (!project.getService(FrameworkProjectStateService::class.java).isEnabled()) {
                finishRequest(preparedRequest.request.key, applied = false)
                return@invokeLater
            }

            val sdkOverlayService = project.getService(FrameworkSdkOverlayService::class.java)
            var appliedAny = false
            var revertedAny = false
            var hadFailure = false
            WriteAction.runAndWait<RuntimeException> {
                val allAndroidModules = allAndroidModules()
                val targetModules = preparedRequest.request.moduleTargets.mapTo(linkedSetOf()) { it.module }
                removeLegacyProjectLibrary(allAndroidModules)
                sdkOverlayService.cleanupSdkDefinitions()
                revertedAny = restoreExcludedModules(
                    allAndroidModules = allAndroidModules,
                    targetModules = targetModules,
                    sdkOverlayService = sdkOverlayService,
                )

                preparedRequest.request.moduleTargets.forEach { target ->
                    when (val prepared = preparedRequest.preparedByKey[
                        overlayKey(target.baseSdk, preparedRequest.request.viewPreference)
                    ]) {
                        is PreparedResult.Prepared -> {
                            val overlaySdk = sdkOverlayService.applyPreparedOverlay(prepared.spec)
                            if (ModuleRootManager.getInstance(target.module).sdk?.name != overlaySdk.name) {
                                ModuleRootModificationUtil.setModuleSdk(target.module, overlaySdk)
                                appliedAny = true
                            }
                        }

                        is PreparedResult.Failed -> {
                            hadFailure = true
                            FrameworkNotifier.warning(
                                project,
                                "Framework-First skipped ${prepared.baseSdkName}: ${prepared.reason}",
                            )
                        }

                        null -> Unit
                    }
                }
            }

            if (appliedAny || revertedAny) {
                PsiManager.getInstance(project).dropPsiCaches()
                DaemonCodeAnalyzer.getInstance(project).restart(project)
            } else if (hadFailure && preparedRequest.request.viewPreference == FrameworkViewPreference.FRAMEWORK_FIRST) {
                disableOverlay()
            }
            finishRequest(preparedRequest.request.key, applied = appliedAny)
        }
    }

    private fun disableOverlay() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }

            val sdkOverlayService = project.getService(FrameworkSdkOverlayService::class.java)
            var revertedAny = false
            WriteAction.runAndWait<RuntimeException> {
                val androidModules = allAndroidModules()
                removeLegacyProjectLibrary(androidModules)
                androidModules.forEach { module ->
                    val currentSdk = ModuleRootManager.getInstance(module).sdk ?: return@forEach
                    val baseSdk = sdkOverlayService.resolveBaseSdk(currentSdk)
                    if (currentSdk.name != baseSdk.name) {
                        ModuleRootModificationUtil.setModuleSdk(module, baseSdk)
                        revertedAny = true
                    }
                }
                sdkOverlayService.cleanupSdkDefinitions()
            }

            synchronized(stateLock) {
                lastAppliedRequestKey = null
            }
            if (revertedAny) {
                PsiManager.getInstance(project).dropPsiCaches()
                DaemonCodeAnalyzer.getInstance(project).restart(project)
            }
            notifyListeners()
        }
    }

    private fun finishRequest(requestKey: String, applied: Boolean) {
        val shouldReschedule = synchronized(stateLock) {
            if (runningRequestKey == requestKey) {
                runningRequestKey = null
            }
            if (applied) {
                lastAppliedRequestKey = requestKey
            }
            val pending = rescheduleRequested
            rescheduleRequested = false
            pending
        }

        if (shouldReschedule && !project.isDisposed) {
            syncOverlay("coalesced")
        }
        notifyListeners()
    }

    private fun overlayKey(
        baseSdk: Sdk,
        viewPreference: FrameworkViewPreference,
    ): String {
        return baseSdk.name + "|" + baseSdk.sdkType.name + "|" + viewPreference.storageValue
    }

    private fun targetModules(): List<Module> {
        return FrameworkProjectUtil.overlayTargetModules(project)
    }

    private fun allAndroidModules(): List<Module> {
        return FrameworkProjectUtil.androidFacetModules(project)
    }

    private fun restoreExcludedModules(
        allAndroidModules: List<Module>,
        targetModules: Set<Module>,
        sdkOverlayService: FrameworkSdkOverlayService,
    ): Boolean {
        var revertedAny = false
        allAndroidModules
            .filterNot(targetModules::contains)
            .forEach { module ->
                val currentSdk = ModuleRootManager.getInstance(module).sdk ?: return@forEach
                if (!sdkOverlayService.isUsableManagedOverlaySdk(currentSdk)) {
                    return@forEach
                }
                val baseSdk = sdkOverlayService.resolveBaseSdk(currentSdk)
                if (currentSdk.name != baseSdk.name) {
                    ModuleRootModificationUtil.setModuleSdk(module, baseSdk)
                    revertedAny = true
                }
            }
        return revertedAny
    }

    private fun removeLegacyProjectLibrary(modules: List<Module>) {
        val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        val legacyLibrary = table.getLibraryByName(LEGACY_LIBRARY_NAME)
        modules.forEach { module ->
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.orderEntries
                    .filterIsInstance<LibraryOrderEntry>()
                    .filter { it.libraryName == LEGACY_LIBRARY_NAME }
                    .forEach(model::removeOrderEntry)
            }
        }
        if (legacyLibrary != null) {
            val tableModel = table.modifiableModel
            tableModel.removeLibrary(legacyLibrary)
            tableModel.commit()
        }
    }

    private fun notifyListeners() {
        listeners.forEach { listener ->
            runCatching(listener)
        }
    }

    private data class ModuleTarget(
        val module: Module,
        val currentSdk: Sdk,
        val baseSdk: Sdk,
    )

    private data class OverlayRequest(
        val trigger: String,
        val key: String,
        val frameworkJar: java.nio.file.Path,
        val viewPreference: FrameworkViewPreference,
        val moduleTargets: List<ModuleTarget>,
    )

    private data class PreparedRequest(
        val request: OverlayRequest,
        val preparedByKey: Map<String, PreparedResult>,
    )

    private sealed interface PreparedResult {
        data class Prepared(val spec: FrameworkSdkOverlayService.PreparedOverlaySpec) : PreparedResult
        data class Failed(val baseSdkName: String, val reason: String) : PreparedResult
    }

    private companion object {
        const val LEGACY_LIBRARY_NAME = "Framework First Overlay"
    }
}
