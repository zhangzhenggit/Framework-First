package com.lenovo.tools.frameworkfirst

import com.android.sdklib.IAndroidTarget
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
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

@Service(Service.Level.PROJECT)
class FrameworkSdkOverlayService(private val project: Project) {
    fun resolveBaseSdk(sdk: Sdk): Sdk {
        val table = ProjectJdkTable.getInstance()
        val baseName = baseSdkName(sdk.name)
        return table.findJdk(baseName, sdk.sdkType.name) ?: sdk
    }

    fun ensureOverlaySdk(
        baseSdk: Sdk,
        frameworkJarFile: VirtualFile,
    ): Sdk {
        val baseAdditionalData = AndroidSdkAdditionalData.from(baseSdk)
        val targetHash = resolveTargetHash(baseSdk, baseAdditionalData)
        val basePlatformDir = resolveBasePlatformDir(baseSdk, baseAdditionalData, targetHash)
            ?: error("Cannot resolve base platform dir for ${baseSdk.name}")
        val frameworkJarPath = frameworkJarFile.toNioPath().normalize()
        val fingerprint = buildFingerprint(baseSdk, frameworkJarPath, targetHash)
        val overlayName = overlaySdkName(baseSdk.name, fingerprint)
        val table = ProjectJdkTable.getInstance()
        val overlaySdk = table.findJdk(overlayName, baseSdk.sdkType.name)
            ?: createOverlaySdk(table, baseSdk, overlayName)

        syncOverlaySdk(
            overlaySdk = overlaySdk,
            overlayName = overlayName,
            baseSdk = baseSdk,
            frameworkJarPath = frameworkJarPath,
            basePlatformDir = basePlatformDir,
            targetHash = targetHash,
            fingerprint = fingerprint,
        )
        return overlaySdk
    }

    fun cleanupLegacyArtifacts() {
        cleanupLegacyProjectCache()
        cleanupLegacyOverlaySdks()
    }

    private fun overlaySdkName(baseSdkName: String, fingerprint: String): String {
        return "${baseSdkName(baseSdkName)} [framework-first:${fingerprint.take(SHORT_HASH_LENGTH)}]"
    }

    private fun baseSdkName(name: String): String {
        return name
            .removeSuffix(LEGACY_SDK_SUFFIX)
            .replace(FINGERPRINT_SUFFIX_REGEX, "")
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
        frameworkJarPath: Path,
        basePlatformDir: Path,
        targetHash: String?,
        fingerprint: String,
    ) {
        val overlayHome = ensureSyntheticSdkHome(
            baseSdk = baseSdk,
            basePlatformDir = basePlatformDir,
            frameworkJarPath = frameworkJarPath,
            targetHash = targetHash,
            fingerprint = fingerprint,
        )
        val overlayPlatformDir = overlayHome.resolve("platforms").resolve(basePlatformDir.fileName.toString())
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
        val desiredSourceRoots = deduplicateRoots(mapRootsToOverlay(baseSourceRoots, baseSdk, overlayHome))
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
        baseSdk: Sdk,
        basePlatformDir: Path,
        frameworkJarPath: Path,
        targetHash: String?,
        fingerprint: String,
    ): Path {
        val cacheEntryRoot = cacheRoot().resolve(fingerprint)
        val overlayHome = cacheEntryRoot.resolve("sdk")
        val overlayPlatformDir = overlayHome.resolve("platforms").resolve(basePlatformDir.fileName.toString())
        val baseAndroidJar = basePlatformDir.resolve(ANDROID_JAR_NAME)
        val mergedAndroidJar = overlayPlatformDir.resolve(ANDROID_JAR_NAME)
        val stampFile = cacheEntryRoot.resolve(STAMP_FILE_NAME)
        val accessFile = cacheEntryRoot.resolve(ACCESS_FILE_NAME)
        val expectedStamp = buildStamp(baseSdk, basePlatformDir, baseAndroidJar, frameworkJarPath, targetHash)

        cleanupExpiredCaches(cacheRoot(), keepFingerprint = fingerprint)

        if (Files.isRegularFile(stampFile) &&
            Files.exists(mergedAndroidJar) &&
            Files.readString(stampFile) == expectedStamp
        ) {
            touchCache(accessFile)
            return overlayHome
        }

        recreateDirectory(overlayPlatformDir)
        copyDirectory(basePlatformDir, overlayPlatformDir)
        AndroidJarMerger.merge(
            baseAndroidJar = baseAndroidJar,
            frameworkJar = frameworkJarPath,
            outputJar = mergedAndroidJar,
        )
        Files.createDirectories(cacheEntryRoot)
        Files.writeString(stampFile, expectedStamp)
        touchCache(accessFile)
        return overlayHome
    }

    private fun cleanupExpiredCaches(cacheRoot: Path, keepFingerprint: String) {
        if (!Files.isDirectory(cacheRoot)) {
            return
        }

        val expireBefore = System.currentTimeMillis() - CACHE_TTL.toMillis()
        Files.list(cacheRoot).use { entries ->
            entries.filter(Files::isDirectory).forEach { entry ->
                if (entry.fileName.toString() == keepFingerprint) {
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
    ): List<VirtualFile> {
        val baseHome = baseSdk.homePath?.let(Path::of)?.normalize() ?: return roots
        return roots.mapNotNull { root ->
            mapRootToOverlay(root, baseHome, overlayHome) ?: root
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

    private fun buildFingerprint(
        baseSdk: Sdk,
        frameworkJarPath: Path,
        targetHash: String?,
    ): String {
        val basePlatformDir = resolveBasePlatformDir(baseSdk, AndroidSdkAdditionalData.from(baseSdk), targetHash)
            ?: error("Cannot resolve base platform dir for ${baseSdk.name}")
        val baseAndroidJar = basePlatformDir.resolve(ANDROID_JAR_NAME)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CACHE_SCHEMA_VERSION.toByteArray())
        digest.update(baseSdk.sdkType.name.toByteArray())
        digest.update(baseSdk.homePath.orEmpty().toByteArray())
        digest.update(targetHash.orEmpty().toByteArray())
        digest.update(Files.size(baseAndroidJar).toString().toByteArray())
        digest.update(Files.getLastModifiedTime(baseAndroidJar).toMillis().toString().toByteArray())
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
    ): String {
        return buildString {
            appendLine("schema=$CACHE_SCHEMA_VERSION")
            appendLine("baseSdk=${baseSdk.name}")
            appendLine("baseHome=${baseSdk.homePath.orEmpty()}")
            appendLine("platformDir=$basePlatformDir")
            appendLine("targetHash=${targetHash.orEmpty()}")
            appendLine("baseAndroidJar=$baseAndroidJar")
            appendLine("baseAndroidJarSize=${Files.size(baseAndroidJar)}")
            appendLine("baseAndroidJarMtime=${Files.getLastModifiedTime(baseAndroidJar).toMillis()}")
            appendLine("frameworkJar=$frameworkJarPath")
            appendLine("frameworkJarSize=${Files.size(frameworkJarPath)}")
            appendLine("frameworkJarMtime=${Files.getLastModifiedTime(frameworkJarPath).toMillis()}")
        }
    }

    private fun deduplicateRoots(roots: List<VirtualFile>): List<VirtualFile> {
        val seen = linkedSetOf<String>()
        return roots.filter { seen.add(it.url) }
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

    private companion object {
        val FINGERPRINT_SUFFIX_REGEX = Regex(""" \[framework-first:[0-9a-f]{8}]$""")
        val CACHE_TTL: Duration = Duration.ofDays(30)
        const val LEGACY_SDK_SUFFIX = " [framework-first]"
        const val LEGACY_PROJECT_CACHE_DIR = ".framework-first-sdk"
        const val CACHE_SCHEMA_VERSION = "4"
        const val CACHE_ROOT_DIR = "Framework-First/cache"
        const val ANDROID_JAR_NAME = "android.jar"
        const val STAMP_FILE_NAME = ".stamp"
        const val ACCESS_FILE_NAME = ".last-access"
        const val SHORT_HASH_LENGTH = 8
    }
}
