package com.lenovo.tools.frameworkfirst

import com.android.sdklib.IAndroidTarget
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.android.sdk.AndroidSdkAdditionalData
import org.jetbrains.android.sdk.StudioAndroidSdkData
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

@Service(Service.Level.PROJECT)
class FrameworkSdkOverlayService(private val project: Project) {
    private val logger = Logger.getInstance(FrameworkSdkOverlayService::class.java)
    @Volatile
    private var gcRunning: Boolean = false

    fun resolveBaseSdk(sdk: Sdk): Sdk {
        val table = ProjectJdkTable.getInstance()
        val baseName = baseSdkName(sdk.name)
        return table.findJdk(baseName, sdk.sdkType.name) ?: sdk
    }

    fun prepareOverlay(
        baseSdk: Sdk,
        frameworkJarPath: Path,
        viewPreference: FrameworkViewPreference,
    ): PreparedOverlaySpec {
        val baseAdditionalData = AndroidSdkAdditionalData.from(baseSdk)
        val targetHash = resolveTargetHash(baseSdk, baseAdditionalData)
        val basePlatformDir = resolveBasePlatformDir(baseSdk, baseAdditionalData, targetHash)
            ?: error("Cannot resolve base platform dir for ${baseSdk.name}")
        val fingerprint = buildFingerprint(baseSdk, frameworkJarPath, targetHash, viewPreference)
        val overlayName = overlaySdkName(baseSdk.name, viewPreference, fingerprint)
        val overlayHome = ensureSyntheticSdkHome(
            overlayName = overlayName,
            baseSdk = baseSdk,
            frameworkJarPath = frameworkJarPath,
            basePlatformDir = basePlatformDir,
            targetHash = targetHash,
            fingerprint = fingerprint,
            viewPreference = viewPreference,
        )
        return PreparedOverlaySpec(
            baseSdk = baseSdk,
            overlayName = overlayName,
            overlayHome = overlayHome,
            platformDirName = basePlatformDir.fileName.toString(),
            targetHash = targetHash,
            viewPreference = viewPreference,
        )
    }

    fun applyPreparedOverlay(prepared: PreparedOverlaySpec): Sdk {
        val table = ProjectJdkTable.getInstance()
        val overlaySdk = table.findJdk(prepared.overlayName, prepared.baseSdk.sdkType.name)
            ?: createOverlaySdk(table, prepared.baseSdk, prepared.overlayName)

        syncOverlaySdk(
            overlaySdk = overlaySdk,
            overlayName = prepared.overlayName,
            baseSdk = prepared.baseSdk,
            overlayHome = prepared.overlayHome,
            platformDirName = prepared.platformDirName,
            targetHash = prepared.targetHash,
            viewPreference = prepared.viewPreference,
        )
        return overlaySdk
    }

    fun cleanupDiskArtifacts() {
        cleanupExpiredCaches(cacheRoot(), keepFingerprint = null)
        cleanupLegacyProjectCache()
    }

    fun cleanupObsoleteSchemaCaches(activeOverlayHomes: Collection<Path>) {
        val keepCacheEntries = activeOverlayHomes.mapNotNull { overlayHome ->
            overlayHome.parent?.normalize()
        }.toSet()
        val archivedAny = if (keepCacheEntries.isNotEmpty()) {
            archiveObsoleteSchemaCaches(keepCacheEntries)
        } else {
            false
        }
        if (archivedAny) {
            cleanupSdkDefinitions()
        }
        deletePendingGcArtifactsAsync()
    }

    fun archiveObsoleteSchemaCaches(keepCacheEntries: Set<Path>): Boolean {
        val currentSchemaRoot = currentSchemaCacheRoot().normalize()
        if (!Files.isDirectory(currentSchemaRoot.parent)) {
            return false
        }
        var archivedAny = false
        Files.createDirectories(gcRoot())
        Files.list(currentSchemaRoot.parent).use { entries ->
            entries.filter(Files::isDirectory).forEach { entry ->
                val normalizedEntry = entry.normalize()
                if (normalizedEntry == currentSchemaRoot || normalizedEntry in keepCacheEntries) {
                    return@forEach
                }
                val archivedPath = uniqueGcPath(entry.fileName.toString())
                runCatching {
                    Files.move(normalizedEntry, archivedPath)
                }.onSuccess {
                    archivedAny = true
                }.onFailure { throwable ->
                    logger.warn("Failed to archive obsolete Framework-First schema cache $normalizedEntry", throwable)
                }
            }
        }
        return archivedAny
    }

    fun hasPendingGcArtifacts(): Boolean {
        val gcRoot = gcRoot()
        if (!Files.isDirectory(gcRoot)) {
            return false
        }
        Files.list(gcRoot).use { entries ->
            return entries.anyMatch(Files::exists)
        }
    }

    fun deletePendingGcArtifactsAsync() {
        if (gcRunning) {
            return
        }
        gcRunning = true
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                deletePendingGcArtifacts()
            } finally {
                gcRunning = false
            }
        }
    }

    fun cleanupSdkDefinitions() {
        cleanupLegacyOverlaySdks()
        cleanupStaleOverlaySdks()
    }

    fun isUsableManagedOverlaySdk(
        sdk: Sdk,
        expectedPreference: FrameworkViewPreference? = null,
    ): Boolean {
        return isManagedOverlaySdk(sdk) &&
            !isStaleOverlaySdk(sdk, currentSchemaCacheRoot().normalize()) &&
            (expectedPreference == null || managedOverlayMode(sdk) == expectedPreference)
    }

    fun isExpectedManagedOverlaySdk(
        sdk: Sdk,
        baseSdk: Sdk,
        frameworkJarPath: Path,
        viewPreference: FrameworkViewPreference,
    ): Boolean {
        if (!isUsableManagedOverlaySdk(sdk, viewPreference)) {
            return false
        }
        val currentHome = sdk.homePath
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it).normalize() }.getOrNull() }
            ?: return false
        val baseAdditionalData = AndroidSdkAdditionalData.from(baseSdk)
        val targetHash = resolveTargetHash(baseSdk, baseAdditionalData)
        val expectedFingerprint = buildFingerprint(baseSdk, frameworkJarPath, targetHash, viewPreference)
        val expectedHome = currentSchemaCacheRoot().resolve(expectedFingerprint).resolve("sdk").normalize()
        if (currentHome != expectedHome) {
            return false
        }
        return Files.isRegularFile(currentHome.resolve("platforms").resolve(resolvePlatformDirName(baseSdk, baseAdditionalData, targetHash)).resolve(ANDROID_JAR_NAME))
    }

    private fun overlaySdkName(
        baseSdkName: String,
        viewPreference: FrameworkViewPreference,
        fingerprint: String,
    ): String {
        return "${baseSdkName(baseSdkName)} [framework-first:${viewPreference.sdkTag}:${fingerprint.take(SHORT_HASH_LENGTH)}]"
    }

    private fun baseSdkName(name: String): String {
        return name
            .removeSuffix(LEGACY_SDK_SUFFIX)
            .replace(MANAGED_SDK_SUFFIX_REGEX, "")
    }

    private fun createOverlaySdk(
        table: ProjectJdkTable,
        baseSdk: Sdk,
        overlayName: String,
    ): Sdk {
        val cloned = baseSdk.clone()
        cloned.sdkModificator.apply {
            setName(overlayName)
            commitChanges()
        }
        table.addJdk(cloned)
        return table.findJdk(overlayName, baseSdk.sdkType.name) ?: cloned
    }

    private fun syncOverlaySdk(
        overlaySdk: Sdk,
        overlayName: String,
        baseSdk: Sdk,
        overlayHome: Path,
        platformDirName: String,
        targetHash: String?,
        viewPreference: FrameworkViewPreference,
    ) {
        val overlayPlatformDir = overlayHome.resolve("platforms").resolve(platformDirName)
        val overlayAndroidJar = overlayPlatformDir.resolve(ANDROID_JAR_NAME)
        val overlayAndroidJarFile = VfsUtil.findFile(overlayAndroidJar, true)
            ?: error("Cannot resolve overlay android.jar at $overlayAndroidJar")
        val overlayAndroidJarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(overlayAndroidJarFile)
            ?: error("Cannot resolve overlay android.jar root for $overlayAndroidJar")

        val baseClassRoots = baseSdk.rootProvider.getFiles(OrderRootType.CLASSES).toList()
        val baseSourceRoots = baseSdk.rootProvider.getFiles(OrderRootType.SOURCES).toList()
        val desiredClassRoots = deduplicateRoots(
            listOf(overlayAndroidJarRoot) + mapRootsToOverlay(baseClassRoots, baseSdk, overlayHome),
        )
        val desiredSourceRoots = when (viewPreference) {
            FrameworkViewPreference.SDK_FIRST -> {
                deduplicateRoots(baseSourceRoots)
            }

            FrameworkViewPreference.FRAMEWORK_FIRST -> {
                deduplicateRoots(
                    mapRootsToOverlay(
                        baseSourceRoots,
                        baseSdk,
                        overlayHome,
                        fallbackToOriginal = true,
                    ),
                )
            }
        }
        val overlayAdditionalData = AndroidSdkAdditionalData(overlaySdk).apply {
            if (!targetHash.isNullOrBlank()) {
                setBuildTargetHashString(targetHash)
            }
        }

        overlaySdk.sdkModificator.apply {
            setName(overlayName)
            setHomePath(overlayHome.toString())
            setVersionString(baseSdk.versionString)
            setSdkAdditionalData(overlayAdditionalData)
            replaceRoots(this, OrderRootType.CLASSES, desiredClassRoots)
            replaceRoots(this, OrderRootType.SOURCES, desiredSourceRoots)
            commitChanges()
        }
    }

    private fun replaceRoots(
        modificator: SdkModificator,
        rootType: OrderRootType,
        desiredRoots: List<VirtualFile>,
    ) {
        modificator.removeRoots(rootType)
        desiredRoots.forEach { modificator.addRoot(it, rootType) }
    }

    private fun resolveTargetHash(
        baseSdk: Sdk,
        baseAdditionalData: AndroidSdkAdditionalData?,
    ): String? {
        val hashFromAdditionalData = baseAdditionalData?.buildTargetHashString?.takeIf { it.isNotBlank() }
        if (hashFromAdditionalData != null) {
            return hashFromAdditionalData
        }
        val baseTarget = resolveBaseTarget(baseSdk, baseAdditionalData)
        return baseTarget?.hashString()
    }

    private fun resolveBasePlatformDir(
        baseSdk: Sdk,
        baseAdditionalData: AndroidSdkAdditionalData?,
        targetHash: String?,
    ): Path? {
        val baseTarget = resolveBaseTarget(baseSdk, baseAdditionalData)
        val targetPlatformDir = baseTarget
            ?.getPath(IAndroidTarget.ANDROID_JAR)
            ?.parent
        if (targetPlatformDir != null && Files.isDirectory(targetPlatformDir)) {
            return targetPlatformDir
        }

        val baseHome = baseSdk.homePath?.let(Path::of)?.normalize() ?: return null
        return targetHash
            ?.let { baseHome.resolve("platforms").resolve(it) }
            ?.takeIf(Files::isDirectory)
    }

    private fun resolvePlatformDirName(
        baseSdk: Sdk,
        baseAdditionalData: AndroidSdkAdditionalData?,
        targetHash: String?,
    ): String {
        return resolveBasePlatformDir(baseSdk, baseAdditionalData, targetHash)?.fileName?.toString()
            ?: targetHash
            ?: error("Cannot resolve platform directory name for ${baseSdk.name}")
    }

    private fun resolveBaseTarget(
        baseSdk: Sdk,
        baseAdditionalData: AndroidSdkAdditionalData?,
    ): IAndroidTarget? {
        return baseAdditionalData?.androidPlatform?.target
            ?: StudioAndroidSdkData.getSdkData(baseSdk)?.let { sdkData ->
                baseAdditionalData?.getBuildTarget(sdkData)
            }
    }

    private fun ensureSyntheticSdkHome(
        overlayName: String,
        baseSdk: Sdk,
        frameworkJarPath: Path,
        basePlatformDir: Path,
        targetHash: String?,
        fingerprint: String,
        viewPreference: FrameworkViewPreference,
    ): Path {
        val cacheEntryRoot = currentSchemaCacheRoot().resolve(fingerprint)
        val overlayHome = cacheEntryRoot.resolve("sdk")
        val overlayPlatformDir = overlayHome.resolve("platforms").resolve(basePlatformDir.fileName.toString())
        val baseAndroidJar = basePlatformDir.resolve(ANDROID_JAR_NAME)
        val mergedAndroidJar = overlayPlatformDir.resolve(ANDROID_JAR_NAME)
        val stampFile = cacheEntryRoot.resolve(STAMP_FILE_NAME)
        val accessFile = cacheEntryRoot.resolve(ACCESS_FILE_NAME)
        val expectedStamp = buildStamp(
            baseSdk = baseSdk,
            basePlatformDir = basePlatformDir,
            baseAndroidJar = baseAndroidJar,
            frameworkJarPath = frameworkJarPath,
            targetHash = targetHash,
            viewPreference = viewPreference,
        )

        cleanupExpiredCaches(currentSchemaCacheRoot(), keepFingerprint = fingerprint)

        if (Files.isRegularFile(stampFile) &&
            Files.exists(mergedAndroidJar) &&
            Files.readString(stampFile) == expectedStamp
        ) {
            touchCache(accessFile)
            return overlayHome
        }

        recreateDirectory(overlayPlatformDir)
        copyDirectory(basePlatformDir, overlayPlatformDir)
        val mergeReport = AndroidJarMerger.merge(
            baseAndroidJar = baseAndroidJar,
            frameworkJar = frameworkJarPath,
            outputJar = mergedAndroidJar,
            strategy = when (viewPreference) {
                FrameworkViewPreference.SDK_FIRST -> AndroidJarMergeStrategy.SDK_FIRST
                FrameworkViewPreference.FRAMEWORK_FIRST -> AndroidJarMergeStrategy.FRAMEWORK_FIRST
            },
        )
        if (viewPreference == FrameworkViewPreference.FRAMEWORK_FIRST) {
            validateFrameworkFirstOverlay(mergedAndroidJar)
            prepareFrameworkPreferredSources(baseSdk, overlayHome, frameworkJarPath)
        }
        Files.createDirectories(cacheEntryRoot)
        Files.writeString(stampFile, expectedStamp)
        writeMergeReport(cacheEntryRoot.resolve(MERGE_REPORT_FILE_NAME), mergeReport)
        touchCache(accessFile)
        logMergeReport(overlayName, fingerprint, mergeReport)
        return overlayHome
    }

    private fun cleanupExpiredCaches(cacheRoot: Path, keepFingerprint: String?) {
        if (!Files.isDirectory(cacheRoot)) {
            return
        }

        val expireBefore = System.currentTimeMillis() - CACHE_TTL.toMillis()
        Files.list(cacheRoot).use { entries ->
            entries.filter(Files::isDirectory).forEach { entry ->
                if (keepFingerprint != null && entry.fileName.toString() == keepFingerprint) {
                    return@forEach
                }
                val accessFile = entry.resolve(ACCESS_FILE_NAME)
                val lastAccess = if (Files.isRegularFile(accessFile)) {
                    runCatching { Files.readString(accessFile).trim().toLong() }.getOrNull()
                } else {
                    null
                }
                if (lastAccess != null && lastAccess < expireBefore) {
                    runCatching { deleteDirectory(entry) }
                }
            }
        }
    }

    private fun touchCache(accessFile: Path) {
        Files.createDirectories(accessFile.parent)
        Files.writeString(accessFile, System.currentTimeMillis().toString())
    }

    private fun mapRootsToOverlay(
        roots: List<VirtualFile>,
        baseSdk: Sdk,
        overlayHome: Path,
        fallbackToOriginal: Boolean = true,
    ): List<VirtualFile> {
        val baseHome = baseSdk.homePath?.let(Path::of)?.normalize() ?: return roots
        return roots.mapNotNull { root ->
            mapRootToOverlay(root, baseHome, overlayHome) ?: if (fallbackToOriginal) root else null
        }
    }

    private fun mapRootToOverlay(
        root: VirtualFile,
        baseHome: Path,
        overlayHome: Path,
    ): VirtualFile? {
        val rootPath = rootLocalPath(root)?.normalize() ?: return null
        if (!rootPath.startsWith(baseHome)) {
            return null
        }
        val mappedPath = overlayHome.resolve(baseHome.relativize(rootPath).toString()).normalize()
        if (!Files.exists(mappedPath)) {
            return null
        }

        return if (root.fileSystem is JarFileSystem) {
            val mappedLocalFile = VfsUtil.findFile(mappedPath, true) ?: return null
            JarFileSystem.getInstance().getJarRootForLocalFile(mappedLocalFile)
        } else {
            VfsUtil.findFile(mappedPath, true)
        }
    }

    private fun rootLocalPath(root: VirtualFile): Path? {
        return if (root.fileSystem is JarFileSystem) {
            JarFileSystem.getInstance().getVirtualFileForJar(root)?.let { Path.of(it.path) }
        } else {
            root.takeIf { it.isInLocalFileSystem }?.let { Path.of(it.path) }
        }
    }

    private fun prepareFrameworkPreferredSources(
        baseSdk: Sdk,
        overlayHome: Path,
        frameworkJarPath: Path,
    ) {
        val baseHome = baseSdk.homePath?.let(Path::of)?.normalize() ?: return
        val excludedSourcePaths = frameworkSourceExclusions(frameworkJarPath)
        if (excludedSourcePaths.isEmpty()) {
            return
        }

        baseSdk.rootProvider.getFiles(OrderRootType.SOURCES).forEach { sourceRoot ->
            val sourcePath = rootLocalPath(sourceRoot)?.normalize() ?: return@forEach
            if (!sourcePath.startsWith(baseHome)) {
                return@forEach
            }
            val targetPath = overlayHome.resolve(baseHome.relativize(sourcePath).toString()).normalize()
            when {
                sourceRoot.fileSystem is JarFileSystem && Files.isRegularFile(sourcePath) -> {
                    filterSourceJar(sourcePath, targetPath, excludedSourcePaths)
                }

                Files.isDirectory(sourcePath) -> {
                    filterSourceDirectory(sourcePath, targetPath, excludedSourcePaths)
                }
            }
        }
    }

    private fun frameworkSourceExclusions(frameworkJarPath: Path): Set<String> {
        val excluded = linkedSetOf<String>()
        JarFile(frameworkJarPath.toFile()).use { frameworkJar ->
            val entries = frameworkJar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(CLASS_FILE_SUFFIX)) {
                    continue
                }
                if (entry.name.startsWith(META_INF_PREFIX) || EXCLUDED_FRAMEWORK_PREFIXES.any(entry.name::startsWith)) {
                    continue
                }
                val topLevelName = entry.name
                    .removeSuffix(CLASS_FILE_SUFFIX)
                    .substringBefore('$')
                excluded += "$topLevelName.java"
                excluded += "$topLevelName.kt"
            }
        }
        return excluded
    }

    private fun filterSourceDirectory(
        sourceDir: Path,
        targetDir: Path,
        excludedSourcePaths: Set<String>,
    ) {
        recreateDirectory(targetDir)
        Files.walkFileTree(
            sourceDir,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(targetDir.resolve(sourceDir.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relativePath = sourceDir.relativize(file).toString().replace('\\', '/')
                    if (relativePath !in excludedSourcePaths) {
                        Files.copy(
                            file,
                            targetDir.resolve(sourceDir.relativize(file).toString()),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun filterSourceJar(
        sourceJar: Path,
        targetJar: Path,
        excludedSourcePaths: Set<String>,
    ) {
        Files.createDirectories(targetJar.parent)
        JarFile(sourceJar.toFile()).use { jarFile ->
            val manifest = jarFile.manifest
            Files.newOutputStream(targetJar).use { output ->
                JarOutputStream(output, manifest ?: java.util.jar.Manifest()).use { jarOutput ->
                    val writtenEntries = linkedSetOf<String>()
                    if (manifest != null) {
                        writtenEntries += JarFile.MANIFEST_NAME
                    }
                    val entries = jarFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !writtenEntries.add(entry.name)) {
                            continue
                        }
                        if (entry.name in excludedSourcePaths) {
                            continue
                        }
                        val zipEntry = ZipEntry(entry.name).apply {
                            time = 0L
                        }
                        jarOutput.putNextEntry(zipEntry)
                        jarFile.getInputStream(entry).use { input -> input.copyTo(jarOutput) }
                        jarOutput.closeEntry()
                    }
                }
            }
        }
    }

    private fun buildFingerprint(
        baseSdk: Sdk,
        frameworkJarPath: Path,
        targetHash: String?,
        viewPreference: FrameworkViewPreference,
    ): String {
        val basePlatformDir = resolveBasePlatformDir(baseSdk, AndroidSdkAdditionalData.from(baseSdk), targetHash)
            ?: error("Cannot resolve base platform dir for ${baseSdk.name}")
        val baseAndroidJar = basePlatformDir.resolve(ANDROID_JAR_NAME)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CACHE_SCHEMA_VERSION.toByteArray())
        digest.update(viewPreference.storageValue.toByteArray())
        digest.update(baseSdk.sdkType.name.toByteArray())
        digest.update(baseSdk.homePath.orEmpty().toByteArray())
        digest.update(targetHash.orEmpty().toByteArray())
        digest.update(Files.size(baseAndroidJar).toString().toByteArray())
        digest.update(Files.getLastModifiedTime(baseAndroidJar).toMillis().toString().toByteArray())
        updateSourceRootsDigest(digest, baseSdk)
        Files.newInputStream(frameworkJarPath).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun buildStamp(
        baseSdk: Sdk,
        basePlatformDir: Path,
        baseAndroidJar: Path,
        frameworkJarPath: Path,
        targetHash: String?,
        viewPreference: FrameworkViewPreference,
    ): String {
        return buildString {
            appendLine("schema=$CACHE_SCHEMA_VERSION")
            appendLine("viewPreference=${viewPreference.storageValue}")
            appendLine("baseSdk=${baseSdk.name}")
            appendLine("baseHome=${baseSdk.homePath.orEmpty()}")
            appendLine("platformDir=$basePlatformDir")
            appendLine("targetHash=${targetHash.orEmpty()}")
            appendLine("baseAndroidJar=$baseAndroidJar")
            appendLine("baseAndroidJarSize=${Files.size(baseAndroidJar)}")
            appendLine("baseAndroidJarMtime=${Files.getLastModifiedTime(baseAndroidJar).toMillis()}")
            sourceRootStateLines(baseSdk).forEach(::appendLine)
            appendLine("frameworkJar=$frameworkJarPath")
            appendLine("frameworkJarSize=${Files.size(frameworkJarPath)}")
            appendLine("frameworkJarMtime=${Files.getLastModifiedTime(frameworkJarPath).toMillis()}")
        }
    }

    private fun updateSourceRootsDigest(
        digest: MessageDigest,
        baseSdk: Sdk,
    ) {
        sourceRootStateLines(baseSdk)
            .sorted()
            .forEach { line ->
                digest.update(line.toByteArray())
            }
    }

    private fun sourceRootStateLines(baseSdk: Sdk): List<String> {
        val baseHome = baseSdk.homePath?.let(Path::of)?.normalize() ?: return emptyList()
        return baseSdk.rootProvider.getFiles(OrderRootType.SOURCES)
            .mapNotNull { root ->
                val localPath = rootLocalPath(root)?.normalize() ?: return@mapNotNull null
                if (!localPath.startsWith(baseHome)) {
                    return@mapNotNull null
                }
                val kind = if (root.fileSystem is JarFileSystem) "jar" else "dir"
                val exists = Files.exists(localPath)
                val size = if (exists && Files.isRegularFile(localPath)) Files.size(localPath) else -1L
                val modifiedTime = if (exists) Files.getLastModifiedTime(localPath).toMillis() else -1L
                "sourceRoot=$kind|${localPath}|$size|$modifiedTime"
            }
    }

    private fun deduplicateRoots(roots: List<VirtualFile>): List<VirtualFile> {
        val seen = linkedSetOf<String>()
        return roots.filter { seen.add(it.url) }
    }

    private fun validateFrameworkFirstOverlay(mergedAndroidJar: Path) {
        val validation = FrameworkOverlayValidator.validateFrameworkFirstJar(mergedAndroidJar)
        if (validation.isNotEmpty()) {
            error("Framework-first validation failed: ${validation.joinToString("; ")}")
        }
    }

    private fun cleanupStaleOverlaySdks() {
        val table = ProjectJdkTable.getInstance()
        val managedCacheRoot = currentSchemaCacheRoot().normalize()
        table.allJdks
            .filter(::isManagedOverlaySdk)
            .filter { sdk -> isStaleOverlaySdk(sdk, managedCacheRoot) }
            .forEach { sdk ->
                logger.info("Removing stale Framework-First SDK ${sdk.name}")
                table.removeJdk(sdk)
            }
    }

    private fun cleanupLegacyProjectCache() {
        val projectBase = project.basePath?.let(Path::of)?.normalize() ?: return
        val legacyCache = projectBase.resolve(LEGACY_PROJECT_CACHE_DIR)
        if (Files.exists(legacyCache)) {
            runCatching { deleteDirectory(legacyCache) }
        }
    }

    private fun cleanupLegacyOverlaySdks() {
        val projectBase = project.basePath?.let(Path::of)?.normalize() ?: return
        val legacyHome = projectBase.resolve(LEGACY_PROJECT_CACHE_DIR).toString()
        val table = ProjectJdkTable.getInstance()
        table.allJdks
            .filter { sdk ->
                sdk.name.endsWith(LEGACY_SDK_SUFFIX) &&
                    sdk.homePath?.startsWith(legacyHome) == true
            }
            .forEach { sdk -> table.removeJdk(sdk) }
    }

    private fun isManagedOverlaySdk(sdk: Sdk): Boolean {
        return MANAGED_SDK_NAME_REGEX.matches(sdk.name)
    }

    private fun managedOverlayMode(sdk: Sdk): FrameworkViewPreference? {
        val match = MANAGED_SDK_NAME_REGEX.find(sdk.name) ?: return null
        val tag = match.groups[1]?.value ?: return FrameworkViewPreference.SDK_FIRST
        return when (tag) {
            FrameworkViewPreference.SDK_FIRST.sdkTag -> FrameworkViewPreference.SDK_FIRST
            FrameworkViewPreference.FRAMEWORK_FIRST.sdkTag -> FrameworkViewPreference.FRAMEWORK_FIRST
            else -> null
        }
    }

    private fun isStaleOverlaySdk(sdk: Sdk, managedCacheRoot: Path): Boolean {
        val homePath = sdk.homePath?.takeIf { it.isNotBlank() } ?: return true
        val home = runCatching { Path.of(homePath).normalize() }.getOrNull() ?: return true
        if (!Files.isDirectory(home)) {
            return true
        }
        if (!hasPlatformAndroidJar(home)) {
            return true
        }
        if (home.startsWith(managedCacheRoot)) {
            val cacheEntryRoot = home.parent ?: return true
            if (!Files.isRegularFile(cacheEntryRoot.resolve(STAMP_FILE_NAME))) {
                return true
            }
        }
        return false
    }

    private fun hasPlatformAndroidJar(home: Path): Boolean {
        val platformsDir = home.resolve("platforms")
        if (!Files.isDirectory(platformsDir)) {
            return false
        }
        Files.list(platformsDir).use { platforms ->
            return platforms.anyMatch { platformDir ->
                Files.isRegularFile(platformDir.resolve(ANDROID_JAR_NAME))
            }
        }
    }

    private fun writeMergeReport(reportFile: Path, report: AndroidJarMergeReport) {
        Files.writeString(
            reportFile,
            buildString {
                appendLine("mergedExistingClasses=${report.mergedExistingClasses}")
                appendLine("copiedFrameworkOnlyClasses=${report.copiedFrameworkOnlyClasses}")
                appendLine("addedFields=${report.addedFields}")
                appendLine("addedMethods=${report.addedMethods}")
                appendLine("addedInnerClasses=${report.addedInnerClasses}")
                appendLine("mergedClassAnnotations=${report.mergedClassAnnotations}")
                appendLine("mergedClassAttributes=${report.mergedClassAttributes}")
                appendLine("skippedDuplicateFields=${report.skippedDuplicateFields}")
                appendLine("skippedDuplicateMethods=${report.skippedDuplicateMethods}")
                appendLine("fallbackClassCount=${report.fallbackClasses.size}")
                report.fallbackClasses
                    .take(MERGE_REPORT_SAMPLE_LIMIT)
                    .forEach { fallback ->
                        appendLine("fallback=$fallback")
                    }
            },
        )
    }

    private fun logMergeReport(
        overlayName: String,
        fingerprint: String,
        report: AndroidJarMergeReport,
    ) {
        if (report.fallbackClasses.isNotEmpty()) {
            val sample = report.fallbackClasses
                .take(MERGE_REPORT_SAMPLE_LIMIT)
                .joinToString(" | ")
            logger.warn(
                "Framework-First merge fallback for $overlayName " +
                    "[${fingerprint.take(SHORT_HASH_LENGTH)}]: ${report.fallbackClasses.size} class(es). $sample",
            )
            return
        }

        if (report.addedFields > 0 || report.addedMethods > 0 || report.copiedFrameworkOnlyClasses > 0) {
            logger.info(
                "Framework-First prepared $overlayName [${fingerprint.take(SHORT_HASH_LENGTH)}]: " +
                    "classes=${report.mergedExistingClasses}/${report.copiedFrameworkOnlyClasses}, " +
                    "fields=${report.addedFields}, methods=${report.addedMethods}",
            )
        }
    }

    private fun deleteDirectory(directory: Path) {
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun recreateDirectory(directory: Path) {
        if (Files.exists(directory)) {
            deleteDirectory(directory)
        }
        Files.createDirectories(directory)
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(
                        file,
                        target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun cacheRoot(): Path {
        return Path.of(PathManager.getSystemPath()).resolve(CACHE_ROOT_DIR)
    }

    private fun currentSchemaCacheRoot(): Path {
        return cacheRoot().resolve("v$CACHE_SCHEMA_VERSION")
    }

    private fun gcRoot(): Path {
        return Path.of(PathManager.getSystemPath()).resolve(GC_ROOT_DIR)
    }

    private fun uniqueGcPath(entryName: String): Path {
        val gcRoot = gcRoot()
        var attempt = 0
        while (true) {
            val suffix = if (attempt == 0) "" else "-$attempt"
            val candidate = gcRoot.resolve("${System.currentTimeMillis()}-$entryName$suffix")
            if (!Files.exists(candidate)) {
                return candidate
            }
            attempt++
        }
    }

    private fun deletePendingGcArtifacts() {
        val gcRoot = gcRoot()
        if (!Files.isDirectory(gcRoot)) {
            return
        }
        Files.list(gcRoot).use { entries ->
            entries.filter(Files::isDirectory).forEach { entry ->
                runCatching { deleteDirectory(entry) }
                    .onFailure { throwable ->
                        logger.warn("Failed to delete Framework-First GC cache $entry", throwable)
                    }
            }
        }
        if (Files.isDirectory(gcRoot)) {
            runCatching {
                Files.list(gcRoot).use { entries ->
                    if (!entries.findAny().isPresent) {
                        Files.deleteIfExists(gcRoot)
                    }
                }
            }
        }
    }

    private companion object {
        val MANAGED_SDK_SUFFIX_REGEX = Regex(""" \[framework-first(?:(?:\:sdk|\:fw))?:[0-9a-f]{8}]$""")
        val MANAGED_SDK_NAME_REGEX = Regex(""".+ \[framework-first(?::(sdk|fw))?:[0-9a-f]{8}]$""")
        val CACHE_TTL: Duration = Duration.ofDays(30)
        const val LEGACY_SDK_SUFFIX = " [framework-first]"
        const val LEGACY_PROJECT_CACHE_DIR = ".framework-first-sdk"
        const val CACHE_SCHEMA_VERSION = "7"
        const val CACHE_ROOT_DIR = "Framework-First/cache"
        const val GC_ROOT_DIR = "Framework-First/gc"
        const val ANDROID_JAR_NAME = "android.jar"
        const val STAMP_FILE_NAME = ".stamp"
        const val ACCESS_FILE_NAME = ".last-access"
        const val MERGE_REPORT_FILE_NAME = ".merge-report.txt"
        const val MERGE_REPORT_SAMPLE_LIMIT = 10
        const val SHORT_HASH_LENGTH = 8
        const val CLASS_FILE_SUFFIX = ".class"
        const val META_INF_PREFIX = "META-INF/"
        val EXCLUDED_FRAMEWORK_PREFIXES = listOf(
            "java/",
            "javax/",
            "kotlin/",
            "sun/",
            "libcore/",
            "org/apache/harmony/",
        )
    }

    data class PreparedOverlaySpec(
        val baseSdk: Sdk,
        val overlayName: String,
        val overlayHome: Path,
        val platformDirName: String,
        val targetHash: String?,
        val viewPreference: FrameworkViewPreference,
    )
}
