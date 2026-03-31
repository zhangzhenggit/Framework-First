package com.lenovo.tools.frameworkfirst

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.facet.AndroidFacet

object FrameworkProjectUtil {
    fun androidFacetModules(project: Project): List<Module> {
        return ModuleManager.getInstance(project).modules.filter { module ->
            !module.isDisposed && AndroidFacet.getInstance(module) != null
        }
    }

    fun overlayTargetModules(project: Project): List<Module> {
        val androidModules = androidFacetModules(project)
        val moduleNames = androidModules.mapTo(linkedSetOf(), Module::getName)
        return androidModules.filter { module ->
            !moduleNames.contains("${module.name}.main")
        }
    }

    fun hasAndroidFacetModules(project: Project): Boolean {
        return androidFacetModules(project).isNotEmpty()
    }
}
