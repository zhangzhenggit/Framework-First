package com.lenovo.tools.frameworkfirst

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InnerClassNode
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

object AndroidJarMerger {
    fun merge(
        baseAndroidJar: Path,
        frameworkJar: Path,
        outputJar: Path,
    ) {
        Files.createDirectories(outputJar.parent)
        val frameworkClasses = loadFrameworkClasses(frameworkJar)

        JarFile(baseAndroidJar.toFile()).use { baseJar ->
            val manifest = baseJar.manifest
            Files.newOutputStream(outputJar).use { fileOutput ->
                val bufferedOutput = BufferedOutputStream(fileOutput)
                val jarOutput = if (manifest != null) {
                    JarOutputStream(bufferedOutput, manifest)
                } else {
                    JarOutputStream(bufferedOutput)
                }

                jarOutput.use { output ->
                    val writtenEntries = linkedSetOf<String>()
                    if (manifest != null) {
                        writtenEntries += JarFile.MANIFEST_NAME
                    }

                    val baseEntries = baseJar.entries()
                    while (baseEntries.hasMoreElements()) {
                        val entry = baseEntries.nextElement()
                        if (entry.isDirectory || !writtenEntries.add(entry.name)) {
                            continue
                        }

                        val baseBytes = baseJar.getInputStream(entry).use { it.readBytes() }
                        val outputBytes = when {
                            shouldMergeExistingClass(entry.name) -> {
                                frameworkClasses.remove(entry.name)
                                    ?.let { frameworkBytes -> mergeClass(baseBytes, frameworkBytes) }
                                    ?: baseBytes
                            }

                            else -> baseBytes
                        }

                        writeEntry(output, entry.name, outputBytes)
                    }

                    frameworkClasses.entries
                        .asSequence()
                        .filter { shouldCopyFrameworkOnlyClass(it.key) }
                        .sortedBy { it.key }
                        .forEach { (entryName, bytes) ->
                            if (writtenEntries.add(entryName)) {
                                writeEntry(output, entryName, bytes)
                            }
                        }
                }
            }
        }
    }

    private fun loadFrameworkClasses(frameworkJar: Path): MutableMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        JarFile(frameworkJar.toFile()).use { framework ->
            val entries = framework.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !shouldIncludeFrameworkClass(entry.name)) {
                    continue
                }
                result[entry.name] = framework.getInputStream(entry).use { it.readBytes() }
            }
        }
        return result
    }

    private fun shouldIncludeFrameworkClass(entryName: String): Boolean {
        return entryName.endsWith(CLASS_FILE_SUFFIX) &&
            !entryName.startsWith(META_INF_PREFIX) &&
            EXCLUDED_FRAMEWORK_PREFIXES.none(entryName::startsWith)
    }

    private fun shouldMergeExistingClass(entryName: String): Boolean {
        return entryName.endsWith(CLASS_FILE_SUFFIX) &&
            !entryName.endsWith(PACKAGE_INFO_SUFFIX) &&
            !entryName.endsWith(MODULE_INFO_CLASS)
    }

    private fun shouldCopyFrameworkOnlyClass(entryName: String): Boolean {
        return entryName.endsWith(CLASS_FILE_SUFFIX)
    }

    private fun mergeClass(
        baseBytes: ByteArray,
        frameworkBytes: ByteArray,
    ): ByteArray {
        return runCatching {
            val baseNode = readClassNode(baseBytes)
            val frameworkNode = readClassNode(frameworkBytes)
            if (baseNode.name != frameworkNode.name) {
                return@runCatching baseBytes
            }

            baseNode.version = maxOf(baseNode.version, frameworkNode.version)
            if (baseNode.nestHostClass == null) {
                baseNode.nestHostClass = frameworkNode.nestHostClass
            }
            if (baseNode.outerClass == null) {
                baseNode.outerClass = frameworkNode.outerClass
                baseNode.outerMethod = frameworkNode.outerMethod
                baseNode.outerMethodDesc = frameworkNode.outerMethodDesc
            }

            mergeFields(baseNode, frameworkNode)
            mergeMethods(baseNode, frameworkNode)
            mergeInnerClasses(baseNode, frameworkNode)
            baseNode.nestMembers = mergeStringLists(
                current = baseNode.nestMembers,
                incoming = frameworkNode.nestMembers,
            )
            baseNode.permittedSubclasses = mergeStringLists(
                current = baseNode.permittedSubclasses,
                incoming = frameworkNode.permittedSubclasses,
            )

            val writer = ClassWriter(0)
            baseNode.accept(writer)
            writer.toByteArray()
        }.getOrElse { baseBytes }
    }

    private fun readClassNode(bytes: ByteArray): ClassNode {
        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(bytes).accept(classNode, 0)
        return classNode
    }

    private fun mergeFields(
        baseNode: ClassNode,
        frameworkNode: ClassNode,
    ) {
        val existingFields = baseNode.fields
            .mapTo(linkedSetOf()) { field -> "${field.name}:${field.desc}" }
        frameworkNode.fields.forEach { field ->
            val key = "${field.name}:${field.desc}"
            if (existingFields.add(key)) {
                baseNode.fields.add(field)
            }
        }
    }

    private fun mergeMethods(
        baseNode: ClassNode,
        frameworkNode: ClassNode,
    ) {
        val existingMethods = baseNode.methods
            .mapTo(linkedSetOf()) { method -> "${method.name}${method.desc}" }
        frameworkNode.methods.forEach { method ->
            val key = "${method.name}${method.desc}"
            if (existingMethods.add(key)) {
                baseNode.methods.add(method)
            }
        }
    }

    private fun mergeInnerClasses(
        baseNode: ClassNode,
        frameworkNode: ClassNode,
    ) {
        val existingInnerClasses = baseNode.innerClasses
            .mapTo(linkedSetOf(), ::innerClassKey)
        frameworkNode.innerClasses.forEach { innerClass ->
            val key = innerClassKey(innerClass)
            if (existingInnerClasses.add(key)) {
                baseNode.innerClasses.add(innerClass)
            }
        }
    }

    private fun innerClassKey(innerClass: InnerClassNode): String {
        return listOf(
            innerClass.name.orEmpty(),
            innerClass.outerName.orEmpty(),
            innerClass.innerName.orEmpty(),
        ).joinToString("|")
    }

    private fun mergeStringLists(
        current: MutableList<String>?,
        incoming: MutableList<String>?,
    ): MutableList<String>? {
        if (incoming.isNullOrEmpty()) {
            return current
        }

        val merged = current ?: mutableListOf()
        val seen = merged.toMutableSet()
        incoming.forEach { value ->
            if (seen.add(value)) {
                merged.add(value)
            }
        }
        return merged
    }

    private fun writeEntry(
        output: JarOutputStream,
        entryName: String,
        bytes: ByteArray,
    ) {
        val entry = ZipEntry(entryName)
        entry.time = 0L
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    private const val CLASS_FILE_SUFFIX = ".class"
    private const val META_INF_PREFIX = "META-INF/"
    private const val PACKAGE_INFO_SUFFIX = "/package-info.class"
    private const val MODULE_INFO_CLASS = "module-info.class"

    private val EXCLUDED_FRAMEWORK_PREFIXES = listOf(
        "java/",
        "javax/",
        "kotlin/",
        "sun/",
        "libcore/",
        "org/apache/harmony/",
    )
}
