package com.lenovo.tools.frameworkfirst

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandlerBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Files
import java.nio.file.Path

class FrameworkOriginNavigationHandler : GotoDeclarationHandlerBase() {
    override fun getGotoDeclarationTarget(sourceElement: PsiElement?, editor: Editor): PsiElement? {
        sourceElement ?: return null
        val project = sourceElement.project
        val stateService = project.getService(FrameworkProjectStateService::class.java)
        if (!stateService.isEnabled()) {
            return null
        }

        val module = ModuleUtilCore.findModuleForPsiElement(sourceElement) ?: return null
        val currentSdk = ModuleRootManager.getInstance(module).sdk ?: return null
        val sdkOverlayService = project.getService(FrameworkSdkOverlayService::class.java)
        if (!sdkOverlayService.isUsableManagedOverlaySdk(currentSdk)) {
            return null
        }

        val overlayHome = currentSdk.homePath?.let(Path::of)?.normalize() ?: return null
        val baseSdk = sdkOverlayService.resolveBaseSdk(currentSdk)
        val baseHome = baseSdk.homePath?.let(Path::of)?.normalize() ?: return null
        val target = resolveNavigationTarget(sourceElement) ?: return null
        val config = FrameworkOverlayConfigLoader.load(project)

        return mapToOriginalTarget(
            project = project,
            target = target,
            overlayHome = overlayHome,
            baseSdk = baseSdk,
            baseHome = baseHome,
            frameworkJar = config.frameworkJar?.normalize(),
            viewPreference = config.viewPreference,
        )
    }

    private fun resolveNavigationTarget(sourceElement: PsiElement): PsiElement? {
        generateSequence(sourceElement) { current ->
            current.parent?.takeIf { parent -> parent !is PsiFile }
        }.forEach { candidate ->
            resolveFromReferences(candidate)?.let { return it }
        }
        return null
    }

    private fun resolveFromReferences(element: PsiElement): PsiElement? {
        val references = element.references
        if (references.isNotEmpty()) {
            references.forEach { reference ->
                resolveReference(reference)?.let { return it }
            }
        }
        return element.reference?.let(::resolveReference)
    }

    private fun resolveReference(reference: PsiReference): PsiElement? {
        return when (reference) {
            is PsiPolyVariantReference -> {
                val results = reference.multiResolve(false)
                    .mapNotNull { result -> result.element }
                    .distinct()
                results.singleOrNull()
            }

            else -> reference.resolve()
        }
    }

    private fun mapToOriginalTarget(
        project: Project,
        target: PsiElement,
        overlayHome: Path,
        baseSdk: com.intellij.openapi.projectRoots.Sdk,
        baseHome: Path,
        frameworkJar: Path?,
        viewPreference: FrameworkViewPreference,
    ): PsiElement? {
        val virtualFile = target.containingFile?.virtualFile ?: return null
        return when {
            virtualFile.fileSystem is JarFileSystem -> {
                mapJarTarget(project, target, virtualFile, overlayHome, baseSdk, baseHome, frameworkJar, viewPreference)
            }

            virtualFile.isInLocalFileSystem -> {
                mapLocalSourceTarget(project, target, virtualFile.path, overlayHome, baseHome)
            }

            else -> null
        }
    }

    private fun mapLocalSourceTarget(
        project: Project,
        target: PsiElement,
        sourcePathValue: String,
        overlayHome: Path,
        baseHome: Path,
    ): PsiElement? {
        val sourcePath = runCatching { Path.of(sourcePathValue).normalize() }.getOrNull() ?: return null
        if (!sourcePath.startsWith(overlayHome)) {
            return null
        }
        val originalPath = baseHome.resolve(overlayHome.relativize(sourcePath).toString()).normalize()
        if (!Files.isRegularFile(originalPath)) {
            return null
        }
        val originalFile = VfsUtil.findFile(originalPath, true)
            ?.let { file -> PsiManager.getInstance(project).findFile(file) }
            ?: return null
        return mapSourceElement(target, originalFile).element
    }

    private fun mapJarTarget(
        project: Project,
        target: PsiElement,
        jarEntryFile: com.intellij.openapi.vfs.VirtualFile,
        overlayHome: Path,
        baseSdk: com.intellij.openapi.projectRoots.Sdk,
        baseHome: Path,
        frameworkJar: Path?,
        viewPreference: FrameworkViewPreference,
    ): PsiElement? {
        val localJar = JarFileSystem.getInstance()
            .getVirtualFileForJar(jarEntryFile)
            ?.let { file -> runCatching { Path.of(file.path).normalize() }.getOrNull() }
            ?: return null
        if (!localJar.startsWith(overlayHome)) {
            return null
        }

        val entryPath = jarEntryFile.path.substringAfter("!/", "")
        if (entryPath.isBlank()) {
            return null
        }

        val sdkJar = baseHome.resolve(overlayHome.relativize(localJar).toString()).normalize()
        return when {
            entryPath.endsWith(".java") || entryPath.endsWith(".kt") -> {
                mapSourceJarEntry(project, target, sdkJar, entryPath)?.element
            }

            entryPath.endsWith(".class") -> {
                if (viewPreference == FrameworkViewPreference.FRAMEWORK_FIRST) {
                    chooseFrameworkFirstTarget(project, target, baseSdk, sdkJar, frameworkJar, entryPath)
                } else {
                    chooseSdkFirstTarget(project, target, baseSdk, sdkJar, frameworkJar, entryPath)
                }
            }

            else -> null
        }
    }

    private fun mapSourceJarEntry(
        project: Project,
        target: PsiElement,
        originalJar: Path,
        entryPath: String,
    ): NavigationMatch? {
        val originalEntryFile = findJarEntry(project, originalJar, entryPath) ?: return null
        return mapSourceElement(target, originalEntryFile)
    }

    private fun mapCompiledJarEntry(
        project: Project,
        target: PsiElement,
        originalJar: Path,
        entryPath: String,
    ): NavigationMatch? {
        val originalEntryFile = findJarEntry(project, originalJar, entryPath) ?: return null
        return mapCompiledElement(target, originalEntryFile)
    }

    private fun chooseSdkFirstTarget(
        project: Project,
        target: PsiElement,
        baseSdk: com.intellij.openapi.projectRoots.Sdk,
        sdkJar: Path,
        frameworkJar: Path?,
        entryPath: String,
    ): PsiElement? {
        val sdkSourceMatch = mapSourceFromBaseSdk(project, target, baseSdk, entryPath)
        val sdkMatch = mapCompiledJarEntry(project, target, sdkJar, entryPath)
        if (sdkSourceMatch != null) {
            return sdkSourceMatch.element
        }
        if (sdkMatch != null) {
            return sdkMatch.element
        }
        return frameworkJar
            ?.takeIf(Files::isRegularFile)
            ?.let { jarPath -> mapCompiledJarEntry(project, target, jarPath, entryPath) }
            ?.element
    }

    private fun chooseFrameworkFirstTarget(
        project: Project,
        target: PsiElement,
        baseSdk: com.intellij.openapi.projectRoots.Sdk,
        sdkJar: Path,
        frameworkJar: Path?,
        entryPath: String,
    ): PsiElement? {
        val frameworkMatch = frameworkJar
            ?.takeIf(Files::isRegularFile)
            ?.let { jarPath -> mapCompiledJarEntry(project, target, jarPath, entryPath) }
        val sdkSourceMatch = mapSourceFromBaseSdk(project, target, baseSdk, entryPath)
        val sdkMatch = mapCompiledJarEntry(project, target, sdkJar, entryPath)

        return when (target) {
            is PsiMethod, is PsiField -> {
                when {
                    frameworkMatch?.exact == true -> frameworkMatch.element
                    sdkSourceMatch != null -> sdkSourceMatch.element
                    sdkMatch?.exact == true -> sdkMatch.element
                    frameworkMatch != null -> frameworkMatch.element
                    else -> sdkMatch?.element
                }
            }

            else -> frameworkMatch?.element ?: sdkSourceMatch?.element ?: sdkMatch?.element
        }
    }

    private fun mapSourceFromBaseSdk(
        project: Project,
        target: PsiElement,
        baseSdk: com.intellij.openapi.projectRoots.Sdk,
        entryPath: String,
    ): NavigationMatch? {
        val targetClass = when (target) {
            is PsiClass -> target
            is PsiMethod -> target.containingClass
            is PsiField -> target.containingClass
            else -> PsiTreeUtil.getParentOfType(target, PsiClass::class.java, false)
        } ?: return null
        val relativeCandidates = linkedSetOf<String>().apply {
            addAll(sourceRelativeCandidates(entryPath))
            addAll(sourceRelativeCandidates(targetClass))
        }.toList()
        if (relativeCandidates.isEmpty()) {
            return null
        }

        baseSdk.rootProvider.getFiles(com.intellij.openapi.roots.OrderRootType.SOURCES).forEach { sourceRoot ->
            relativeCandidates.forEach { relativePath ->
                val sourceFile = sourceRoot.findFileByRelativePath(relativePath)
                    ?.let { file -> PsiManager.getInstance(project).findFile(file) }
                    ?: return@forEach
                val mapped = mapSourceElement(target, sourceFile)
                if (mapped.exact || target is PsiClass) {
                    return mapped
                }
            }
        }
        return null
    }

    private fun sourceRelativeCandidates(targetClass: PsiClass): List<String> {
        val topLevelClass = generateSequence(targetClass) { current -> current.containingClass }.last()
        val qualifiedName = topLevelClass.qualifiedName ?: return emptyList()
        val basePath = qualifiedName.replace('.', '/')
        return listOf("$basePath.java", "$basePath.kt")
    }

    private fun sourceRelativeCandidates(entryPath: String): List<String> {
        if (!entryPath.endsWith(".class")) {
            return emptyList()
        }
        val topLevelBasePath = entryPath
            .removeSuffix(".class")
            .substringBefore('$')
        return listOf("$topLevelBasePath.java", "$topLevelBasePath.kt")
    }

    private fun findJarEntry(project: Project, jarPath: Path, entryPath: String): PsiFile? {
        if (!Files.isRegularFile(jarPath)) {
            return null
        }
        val localJar = VfsUtil.findFile(jarPath, true) ?: return null
        val jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(localJar) ?: return null
        val entryFile = jarRoot.findFileByRelativePath(entryPath) ?: return null
        return PsiManager.getInstance(project).findFile(entryFile)
    }

    private fun mapSourceElement(target: PsiElement, originalFile: PsiFile): NavigationMatch {
        mapNamedElement(target, originalFile)?.let { return it }
        val targetRange = target.textRange ?: return NavigationMatch(originalFile, exact = target is PsiFile)
        val elementAtOffset = originalFile.findElementAt(targetRange.startOffset.coerceAtMost(originalFile.textLength - 1))
        val mapped = generateSequence(elementAtOffset) { current -> current.parent }
            .firstOrNull { candidate -> isEquivalentNamedElement(target, candidate) }
            ?: originalFile
        return NavigationMatch(mapped, exact = false)
    }

    private fun mapCompiledElement(target: PsiElement, originalFile: PsiFile): NavigationMatch {
        return mapNamedElement(target, originalFile) ?: NavigationMatch(originalFile, exact = target is PsiFile)
    }

    private fun mapNamedElement(target: PsiElement, originalFile: PsiFile): NavigationMatch? {
        if (target is PsiFile) {
            return NavigationMatch(originalFile, exact = true)
        }

        val targetClass = when (target) {
            is PsiClass -> target
            is PsiMethod -> target.containingClass
            is PsiField -> target.containingClass
            else -> PsiTreeUtil.getParentOfType(target, PsiClass::class.java, false)
        } ?: return null

        val originalClass = findMatchingClass(originalFile, targetClass) ?: return null
        return when (target) {
            is PsiClass -> NavigationMatch(originalClass, exact = true)
            is PsiMethod -> {
                val method = findMatchingMethod(originalClass, target)
                if (method != null) NavigationMatch(method, exact = true) else NavigationMatch(originalClass, exact = false)
            }

            is PsiField -> {
                val field = originalClass.findFieldByName(target.name, false)
                if (field != null) NavigationMatch(field, exact = true) else NavigationMatch(originalClass, exact = false)
            }

            else -> NavigationMatch(originalClass, exact = false)
        }
    }

    private fun findMatchingClass(originalFile: PsiFile, targetClass: PsiClass): PsiClass? {
        val targetQualifiedName = targetClass.qualifiedName
        val allClasses = when (originalFile) {
            is PsiClassOwner -> PsiTreeUtil.collectElementsOfType(originalFile, PsiClass::class.java)
            else -> PsiTreeUtil.collectElementsOfType(originalFile, PsiClass::class.java)
        }
        return if (targetQualifiedName != null) {
            allClasses.firstOrNull { psiClass -> psiClass.qualifiedName == targetQualifiedName }
        } else {
            val targetPath = classNamePath(targetClass)
            allClasses.firstOrNull { psiClass -> classNamePath(psiClass) == targetPath }
        }
    }

    private fun findMatchingMethod(originalClass: PsiClass, targetMethod: PsiMethod): PsiMethod? {
        val candidates = if (targetMethod.isConstructor) {
            originalClass.constructors.asList()
        } else {
            originalClass.findMethodsByName(targetMethod.name, false).asList()
        }
        val targetSignature = parameterSignature(targetMethod)
        return candidates.firstOrNull { candidate ->
            parameterSignature(candidate) == targetSignature
        }
    }

    private fun parameterSignature(method: PsiMethod): List<String> {
        return method.parameterList.parameters.map { parameter ->
            parameter.type.canonicalText
        }
    }

    private fun classNamePath(psiClass: PsiClass): List<String> {
        val names = ArrayDeque<String>()
        var current: PsiClass? = psiClass
        while (current != null) {
            current.name?.let(names::addFirst) ?: return emptyList()
            current = current.containingClass
        }
        return names.toList()
    }

    private fun isEquivalentNamedElement(target: PsiElement, candidate: PsiElement): Boolean {
        if (target::class != candidate::class) {
            return false
        }
        return when {
            target is PsiMethod && candidate is PsiMethod -> {
                target.name == candidate.name && parameterSignature(target) == parameterSignature(candidate)
            }

            target is PsiField && candidate is PsiField -> {
                target.name == candidate.name
            }

            target is PsiClass && candidate is PsiClass -> {
                target.qualifiedName == candidate.qualifiedName ||
                    (target.name == candidate.name && classNamePath(target) == classNamePath(candidate))
            }

            target is PsiNamedElement && candidate is PsiNamedElement -> {
                target.name == candidate.name
            }

            else -> false
        }
    }

    private data class NavigationMatch(
        val element: PsiElement,
        val exact: Boolean,
    )
}
