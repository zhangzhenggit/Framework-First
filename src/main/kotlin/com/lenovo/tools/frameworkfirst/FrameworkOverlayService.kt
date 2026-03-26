package com.lenovo.tools.frameworkfirst

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.psi.PsiManager
import org.jetbrains.android.facet.AndroidFacet
import java.nio.file.Files

@Service(Service.Level.PROJECT)
class FrameworkOverlayService(private val project: Project) {
    private val logger = Logger.getInstance(FrameworkOverlayService::class.java)
    private val stateLock = Any()
    private var runningRequestKey: String? = null
    private var lastAppliedRequestKey: String? = null
    private var rescheduleRequested: Boolean = false

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

        val frameworkJar = FrameworkOverlayConfigLoader.load(project).frameworkJar
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

        val requestKey = buildRequestKey(frameworkJar, moduleTargets)
        if (isAlreadyApplied(requestKey, moduleTargets, sdkOverlayService)) {
            return null
        }

        return OverlayRequest(
            trigger = trigger,
            key = requestKey,
            frameworkJar = frameworkJar,
            moduleTargets = moduleTargets,
        )
    }

    private fun buildRequestKey(
        frameworkJar: java.nio.file.Path,
        moduleTargets: List<ModuleTarget>,
    ): String {
        val jarState = buildString {
            append(frameworkJar)
            append('|')
            append(runCatching { Files.size(frameworkJar) }.getOrDefault(-1L))
            append('|')
            append(runCatching { Files.getLastModifiedTime(frameworkJar).toMillis() }.getOrDefault(-1L))
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
        moduleTargets: List<ModuleTarget>,
        sdkOverlayService: FrameworkSdkOverlayService,
    ): Boolean {
        synchronized(stateLock) {
            if (requestKey != lastAppliedRequestKey) {
                return false
            }
        }

        return moduleTargets.all { target ->
            sdkOverlayService.isUsableManagedOverlaySdk(target.currentSdk)
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

            val overlayKey = overlayKey(target.baseSdk)
            preparedByKey.getOrPut(overlayKey) {
                runCatching {
                    PreparedResult.Prepared(
                        sdkOverlayService.prepareOverlay(target.baseSdk, request.frameworkJar),
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

            val sdkOverlayService = project.getService(FrameworkSdkOverlayService::class.java)
            var appliedAny = false
            WriteAction.runAndWait<RuntimeException> {
                removeLegacyProjectLibrary(preparedRequest.request.moduleTargets.map { it.module })
                sdkOverlayService.cleanupSdkDefinitions()

                preparedRequest.request.moduleTargets.forEach { target ->
                    when (val prepared = preparedRequest.preparedByKey[overlayKey(target.baseSdk)]) {
                        is PreparedResult.Prepared -> {
                            val overlaySdk = sdkOverlayService.applyPreparedOverlay(prepared.spec)
                            if (ModuleRootManager.getInstance(target.module).sdk?.name != overlaySdk.name) {
                                ModuleRootModificationUtil.setModuleSdk(target.module, overlaySdk)
                                appliedAny = true
                            }
                        }

                        is PreparedResult.Failed -> {
                            FrameworkNotifier.warning(
                                project,
                                "Framework-First skipped ${prepared.baseSdkName}: ${prepared.reason}",
                            )
                        }

                        null -> Unit
                    }
                }
            }

            if (appliedAny) {
                PsiManager.getInstance(project).dropPsiCaches()
                DaemonCodeAnalyzer.getInstance(project).restart(project)
            }
            finishRequest(preparedRequest.request.key, applied = true)
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
    }

    private fun overlayKey(baseSdk: Sdk): String {
        return baseSdk.name + "|" + baseSdk.sdkType.name
    }

    private fun targetModules(): List<Module> {
        return ModuleManager.getInstance(project).modules.filter { module ->
            !module.isDisposed && AndroidFacet.getInstance(module) != null
        }
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

    private data class ModuleTarget(
        val module: Module,
        val currentSdk: Sdk,
        val baseSdk: Sdk,
    )

    private data class OverlayRequest(
        val trigger: String,
        val key: String,
        val frameworkJar: java.nio.file.Path,
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
