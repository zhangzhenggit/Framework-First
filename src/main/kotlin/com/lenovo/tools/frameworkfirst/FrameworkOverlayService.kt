package com.lenovo.tools.frameworkfirst

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import org.jetbrains.android.facet.AndroidFacet

@Service(Service.Level.PROJECT)
class FrameworkOverlayService(private val project: Project) {
    private val logger = Logger.getInstance(FrameworkOverlayService::class.java)

    fun syncOverlay(trigger: String) {
        if (project.isDisposed) {
            return
        }

        val frameworkJar = FrameworkOverlayConfigLoader.load(project).frameworkJar
        if (frameworkJar == null) {
            return
        }

        val jarFile = VfsUtil.findFile(frameworkJar, true)
        if (jarFile == null || !jarFile.isValid) {
            val message = "Skipped on $trigger: cannot resolve $frameworkJar"
            logger.warn(message)
            FrameworkNotifier.warning(project, message)
            return
        }

        val modules = targetModules()
        if (modules.isEmpty()) {
            return
        }

        val sdkOverlayService = project.getService(FrameworkSdkOverlayService::class.java)
        val moduleTargets = modules.mapNotNull { module ->
            val currentSdk = ModuleRootManager.getInstance(module).sdk ?: return@mapNotNull null
            ModuleTarget(
                module = module,
                baseSdk = sdkOverlayService.resolveBaseSdk(currentSdk),
            )
        }
        if (moduleTargets.isEmpty()) {
            val message = "Skipped on $trigger: Android modules have no SDK"
            logger.warn(message)
            FrameworkNotifier.warning(project, message)
            return
        }

        WriteAction.runAndWait<RuntimeException> {
            removeLegacyProjectLibrary(modules)
            sdkOverlayService.cleanupLegacyArtifacts()

            val overlaysByKey = LinkedHashMap<String, Sdk?>()
            moduleTargets.forEach { target ->
                val overlayKey = target.baseSdk.name + "|" + target.baseSdk.sdkType.name + "|" + frameworkJar
                val overlaySdk = overlaysByKey.getOrPut(overlayKey) {
                    runCatching {
                        sdkOverlayService.ensureOverlaySdk(target.baseSdk, jarFile)
                    }.getOrElse { throwable ->
                        logger.warn("Failed to prepare overlay SDK for ${target.baseSdk.name}", throwable)
                        FrameworkNotifier.warning(
                            project,
                            "Framework-First skipped ${target.baseSdk.name}: ${throwable.message ?: throwable.javaClass.simpleName}",
                        )
                        null
                    }
                }
                if (overlaySdk != null &&
                    ModuleRootManager.getInstance(target.module).sdk?.name != overlaySdk.name
                ) {
                    ModuleRootModificationUtil.setModuleSdk(target.module, overlaySdk)
                }
            }
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            PsiManager.getInstance(project).dropPsiCaches()
            DaemonCodeAnalyzer.getInstance(project).restart(project)
        }
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
        val baseSdk: Sdk,
    )

    private companion object {
        const val LEGACY_LIBRARY_NAME = "Framework First Overlay"
    }
}
