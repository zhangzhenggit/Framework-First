package com.lenovo.tools.frameworkfirst

import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypePath
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InnerClassNode
import org.objectweb.asm.tree.TypeAnnotationNode
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

data class AndroidJarMergeReport(
    val mergedExistingClasses: Int,
    val copiedFrameworkOnlyClasses: Int,
    val addedFields: Int,
    val addedMethods: Int,
    val addedInnerClasses: Int,
    val mergedClassAnnotations: Int,
    val mergedClassAttributes: Int,
    val skippedDuplicateFields: Int,
    val skippedDuplicateMethods: Int,
    val fallbackClasses: List<String>,
)

enum class AndroidJarMergeStrategy {
    SDK_FIRST,
    FRAMEWORK_FIRST,
}

object AndroidJarMerger {
    fun merge(
        baseAndroidJar: Path,
        frameworkJar: Path,
        outputJar: Path,
        strategy: AndroidJarMergeStrategy = AndroidJarMergeStrategy.SDK_FIRST,
    ): AndroidJarMergeReport {
        Files.createDirectories(outputJar.parent)
        val frameworkClasses = loadFrameworkClasses(frameworkJar)
        val stats = MergeStats()

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
                                    ?.let { frameworkBytes ->
                                        val mergeResult = mergeClass(
                                            entryName = entry.name,
                                            sdkBytes = baseBytes,
                                            frameworkBytes = frameworkBytes,
                                            strategy = strategy,
                                        )
                                        stats.recordMerge(mergeResult)
                                        mergeResult.bytes
                                    }
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
                                stats.copiedFrameworkOnlyClasses++
                                writeEntry(output, entryName, bytes)
                            }
                        }
                }
            }
        }

        return stats.toReport()
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
        entryName: String,
        sdkBytes: ByteArray,
        frameworkBytes: ByteArray,
        strategy: AndroidJarMergeStrategy,
    ): ClassMergeResult {
        return runCatching {
            val sdkNode = readClassNode(sdkBytes)
            val frameworkNode = readClassNode(frameworkBytes)
            if (sdkNode.name != frameworkNode.name) {
                return@runCatching ClassMergeResult.fallback(
                    bytes = preferredBytes(strategy, sdkBytes, frameworkBytes),
                    className = entryName,
                    reason = "Mismatched internal name: ${sdkNode.name} vs ${frameworkNode.name}",
                )
            }

            val primaryNode = when (strategy) {
                AndroidJarMergeStrategy.SDK_FIRST -> sdkNode
                AndroidJarMergeStrategy.FRAMEWORK_FIRST -> frameworkNode
            }
            val secondaryNode = when (strategy) {
                AndroidJarMergeStrategy.SDK_FIRST -> frameworkNode
                AndroidJarMergeStrategy.FRAMEWORK_FIRST -> sdkNode
            }

            primaryNode.version = maxOf(primaryNode.version, secondaryNode.version)
            primaryNode.access = mergeVisibilityAccess(primaryNode.access, secondaryNode.access)
            primaryNode.signature = primaryNode.signature ?: secondaryNode.signature
            primaryNode.sourceFile = primaryNode.sourceFile ?: secondaryNode.sourceFile
            primaryNode.sourceDebug = primaryNode.sourceDebug ?: secondaryNode.sourceDebug
            primaryNode.outerClass = primaryNode.outerClass ?: secondaryNode.outerClass
            primaryNode.outerMethod = primaryNode.outerMethod ?: secondaryNode.outerMethod
            primaryNode.outerMethodDesc = primaryNode.outerMethodDesc ?: secondaryNode.outerMethodDesc
            if (primaryNode.nestHostClass == null) {
                primaryNode.nestHostClass = secondaryNode.nestHostClass
            }

            val metadataResult = mergeClassMetadata(primaryNode, secondaryNode)
            val fieldResult = mergeFields(primaryNode, secondaryNode)
            val methodResult = mergeMethods(primaryNode, secondaryNode)
            val innerClassCount = mergeInnerClasses(primaryNode, secondaryNode)
            primaryNode.nestMembers = mergeStringLists(primaryNode.nestMembers, secondaryNode.nestMembers)
            primaryNode.permittedSubclasses = mergeStringLists(
                primaryNode.permittedSubclasses,
                secondaryNode.permittedSubclasses,
            )

            val writer = ClassWriter(0)
            primaryNode.accept(writer)
            ClassMergeResult(
                bytes = writer.toByteArray(),
                mergedExistingClass = true,
                addedFields = fieldResult.added,
                addedMethods = methodResult.added,
                addedInnerClasses = innerClassCount,
                mergedClassAnnotations = metadataResult.annotations,
                mergedClassAttributes = metadataResult.attributes,
                skippedDuplicateFields = fieldResult.duplicates,
                skippedDuplicateMethods = methodResult.duplicates,
                fallback = null,
            )
        }.getOrElse { throwable ->
            ClassMergeResult.fallback(
                bytes = preferredBytes(strategy, sdkBytes, frameworkBytes),
                className = entryName,
                reason = buildFallbackReason(throwable),
            )
        }
    }

    private fun readClassNode(bytes: ByteArray): ClassNode {
        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(bytes).accept(classNode, 0)
        return classNode
    }

    private fun mergeClassMetadata(
        baseNode: ClassNode,
        frameworkNode: ClassNode,
    ): MetadataMergeResult {
        val visibleAnnotations = mergeAnnotations(baseNode.visibleAnnotations, frameworkNode.visibleAnnotations)
        val invisibleAnnotations = mergeAnnotations(baseNode.invisibleAnnotations, frameworkNode.invisibleAnnotations)
        val visibleTypeAnnotations = mergeTypeAnnotations(
            baseNode.visibleTypeAnnotations,
            frameworkNode.visibleTypeAnnotations,
        )
        val invisibleTypeAnnotations = mergeTypeAnnotations(
            baseNode.invisibleTypeAnnotations,
            frameworkNode.invisibleTypeAnnotations,
        )
        val attrs = mergeAttributes(baseNode.attrs, frameworkNode.attrs)

        baseNode.visibleAnnotations = visibleAnnotations.items
        baseNode.invisibleAnnotations = invisibleAnnotations.items
        baseNode.visibleTypeAnnotations = visibleTypeAnnotations.items
        baseNode.invisibleTypeAnnotations = invisibleTypeAnnotations.items
        baseNode.attrs = attrs.items

        return MetadataMergeResult(
            annotations = visibleAnnotations.added + invisibleAnnotations.added +
                visibleTypeAnnotations.added + invisibleTypeAnnotations.added,
            attributes = attrs.added,
        )
    }

    private fun mergeFields(
        baseNode: ClassNode,
        frameworkNode: ClassNode,
    ): MergeCount {
        val existingFields = linkedMapOf<String, org.objectweb.asm.tree.FieldNode>()
        baseNode.fields.forEach { field ->
            existingFields["${field.name}:${field.desc}"] = field
        }
        var added = 0
        var duplicates = 0
        frameworkNode.fields.forEach { field ->
            val key = "${field.name}:${field.desc}"
            val existing = existingFields[key]
            if (existing == null) {
                existingFields[key] = field
                baseNode.fields.add(field)
                added++
            } else {
                existing.access = mergeVisibilityAccess(existing.access, field.access)
                duplicates++
            }
        }
        return MergeCount(added, duplicates)
    }

    private fun mergeMethods(
        baseNode: ClassNode,
        frameworkNode: ClassNode,
    ): MergeCount {
        val existingMethods = linkedMapOf<String, org.objectweb.asm.tree.MethodNode>()
        baseNode.methods.forEach { method ->
            existingMethods["${method.name}${method.desc}"] = method
        }
        var added = 0
        var duplicates = 0
        frameworkNode.methods.forEach { method ->
            val key = "${method.name}${method.desc}"
            val existing = existingMethods[key]
            if (existing == null) {
                existingMethods[key] = method
                baseNode.methods.add(method)
                added++
            } else {
                existing.access = mergeVisibilityAccess(existing.access, method.access)
                duplicates++
            }
        }
        return MergeCount(added, duplicates)
    }

    private fun mergeVisibilityAccess(
        primaryAccess: Int,
        secondaryAccess: Int,
    ): Int {
        val preservedFlags = primaryAccess and VISIBILITY_MASK.inv()
        return preservedFlags or maxVisibility(primaryAccess, secondaryAccess)
    }

    private fun maxVisibility(
        primaryAccess: Int,
        secondaryAccess: Int,
    ): Int {
        return if (visibilityRank(secondaryAccess) > visibilityRank(primaryAccess)) {
            secondaryAccess and VISIBILITY_MASK
        } else {
            primaryAccess and VISIBILITY_MASK
        }
    }

    private fun visibilityRank(access: Int): Int {
        return when {
            access and Opcodes.ACC_PUBLIC != 0 -> 3
            access and Opcodes.ACC_PROTECTED != 0 -> 2
            access and Opcodes.ACC_PRIVATE != 0 -> 0
            else -> 1
        }
    }

    private fun mergeInnerClasses(
        baseNode: ClassNode,
        frameworkNode: ClassNode,
    ): Int {
        val existingInnerClasses = baseNode.innerClasses
            .mapTo(linkedSetOf(), ::innerClassKey)
        var added = 0
        frameworkNode.innerClasses.forEach { innerClass ->
            val key = innerClassKey(innerClass)
            if (existingInnerClasses.add(key)) {
                baseNode.innerClasses.add(innerClass)
                added++
            }
        }
        return added
    }

    private fun innerClassKey(innerClass: InnerClassNode): String {
        return listOf(
            innerClass.name.orEmpty(),
            innerClass.outerName.orEmpty(),
            innerClass.innerName.orEmpty(),
        ).joinToString("|")
    }

    private fun mergeAnnotations(
        current: MutableList<AnnotationNode>?,
        incoming: MutableList<AnnotationNode>?,
    ): AnnotationMergeResult<AnnotationNode> {
        if (incoming.isNullOrEmpty()) {
            return AnnotationMergeResult(current, 0)
        }

        val merged = current ?: mutableListOf()
        val seen = merged.mapTo(linkedSetOf()) { annotation -> annotation.desc }
        var added = 0
        incoming.forEach { annotation ->
            if (seen.add(annotation.desc)) {
                merged.add(annotation)
                added++
            }
        }
        return AnnotationMergeResult(merged, added)
    }

    private fun mergeTypeAnnotations(
        current: MutableList<TypeAnnotationNode>?,
        incoming: MutableList<TypeAnnotationNode>?,
    ): AnnotationMergeResult<TypeAnnotationNode> {
        if (incoming.isNullOrEmpty()) {
            return AnnotationMergeResult(current, 0)
        }

        val merged = current ?: mutableListOf()
        val seen = merged.mapTo(linkedSetOf(), ::typeAnnotationKey)
        var added = 0
        incoming.forEach { annotation ->
            val key = typeAnnotationKey(annotation)
            if (seen.add(key)) {
                merged.add(annotation)
                added++
            }
        }
        return AnnotationMergeResult(merged, added)
    }

    private fun typeAnnotationKey(annotation: TypeAnnotationNode): String {
        return buildString {
            append(annotation.desc)
            append('|')
            append(annotation.typeRef)
            append('|')
            append(typePathKey(annotation.typePath))
        }
    }

    private fun typePathKey(typePath: TypePath?): String {
        return typePath?.toString().orEmpty()
    }

    private fun mergeAttributes(
        current: MutableList<Attribute>?,
        incoming: MutableList<Attribute>?,
    ): AttributeMergeResult {
        if (incoming.isNullOrEmpty()) {
            return AttributeMergeResult(current, 0)
        }

        val merged = current ?: mutableListOf()
        val seen = merged.mapTo(linkedSetOf()) { attribute -> attribute.type }
        var added = 0
        incoming.forEach { attribute ->
            if (seen.add(attribute.type)) {
                merged.add(attribute)
                added++
            }
        }
        return AttributeMergeResult(merged, added)
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

    private fun buildFallbackReason(throwable: Throwable): String {
        val detail = throwable.message?.trim().orEmpty()
        return if (detail.isBlank()) {
            throwable.javaClass.simpleName
        } else {
            "${throwable.javaClass.simpleName}: ${detail.take(MAX_FALLBACK_REASON_LENGTH)}"
        }
    }

    private fun preferredBytes(
        strategy: AndroidJarMergeStrategy,
        sdkBytes: ByteArray,
        frameworkBytes: ByteArray,
    ): ByteArray {
        return when (strategy) {
            AndroidJarMergeStrategy.SDK_FIRST -> sdkBytes
            AndroidJarMergeStrategy.FRAMEWORK_FIRST -> frameworkBytes
        }
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

    private class MergeStats {
        var mergedExistingClasses: Int = 0
        var copiedFrameworkOnlyClasses: Int = 0
        var addedFields: Int = 0
        var addedMethods: Int = 0
        var addedInnerClasses: Int = 0
        var mergedClassAnnotations: Int = 0
        var mergedClassAttributes: Int = 0
        var skippedDuplicateFields: Int = 0
        var skippedDuplicateMethods: Int = 0
        val fallbackClasses: MutableList<String> = mutableListOf()

        fun recordMerge(result: ClassMergeResult) {
            if (result.mergedExistingClass) {
                mergedExistingClasses++
            }
            addedFields += result.addedFields
            addedMethods += result.addedMethods
            addedInnerClasses += result.addedInnerClasses
            mergedClassAnnotations += result.mergedClassAnnotations
            mergedClassAttributes += result.mergedClassAttributes
            skippedDuplicateFields += result.skippedDuplicateFields
            skippedDuplicateMethods += result.skippedDuplicateMethods
            result.fallback?.let(fallbackClasses::add)
        }

        fun toReport(): AndroidJarMergeReport {
            return AndroidJarMergeReport(
                mergedExistingClasses = mergedExistingClasses,
                copiedFrameworkOnlyClasses = copiedFrameworkOnlyClasses,
                addedFields = addedFields,
                addedMethods = addedMethods,
                addedInnerClasses = addedInnerClasses,
                mergedClassAnnotations = mergedClassAnnotations,
                mergedClassAttributes = mergedClassAttributes,
                skippedDuplicateFields = skippedDuplicateFields,
                skippedDuplicateMethods = skippedDuplicateMethods,
                fallbackClasses = fallbackClasses.toList(),
            )
        }
    }

    private data class ClassMergeResult(
        val bytes: ByteArray,
        val mergedExistingClass: Boolean,
        val addedFields: Int,
        val addedMethods: Int,
        val addedInnerClasses: Int,
        val mergedClassAnnotations: Int,
        val mergedClassAttributes: Int,
        val skippedDuplicateFields: Int,
        val skippedDuplicateMethods: Int,
        val fallback: String?,
    ) {
        companion object {
            fun fallback(
                bytes: ByteArray,
                className: String,
                reason: String,
            ): ClassMergeResult {
                return ClassMergeResult(
                    bytes = bytes,
                    mergedExistingClass = false,
                    addedFields = 0,
                    addedMethods = 0,
                    addedInnerClasses = 0,
                    mergedClassAnnotations = 0,
                    mergedClassAttributes = 0,
                    skippedDuplicateFields = 0,
                    skippedDuplicateMethods = 0,
                    fallback = "$className -> $reason",
                )
            }
        }
    }

    private data class MergeCount(
        val added: Int,
        val duplicates: Int,
    )

    private data class MetadataMergeResult(
        val annotations: Int,
        val attributes: Int,
    )

    private data class AnnotationMergeResult<T>(
        val items: MutableList<T>?,
        val added: Int,
    )

    private data class AttributeMergeResult(
        val items: MutableList<Attribute>?,
        val added: Int,
    )

    private const val CLASS_FILE_SUFFIX = ".class"
    private const val META_INF_PREFIX = "META-INF/"
    private const val PACKAGE_INFO_SUFFIX = "/package-info.class"
    private const val MODULE_INFO_CLASS = "module-info.class"
    private const val MAX_FALLBACK_REASON_LENGTH = 200
    private const val VISIBILITY_MASK = Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE

    private val EXCLUDED_FRAMEWORK_PREFIXES = listOf(
        "java/",
        "javax/",
        "kotlin/",
        "sun/",
        "libcore/",
        "org/apache/harmony/",
    )
}
