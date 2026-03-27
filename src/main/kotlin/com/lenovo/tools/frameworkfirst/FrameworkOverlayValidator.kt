package com.lenovo.tools.frameworkfirst

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Path
import java.util.jar.JarFile

object FrameworkOverlayValidator {
    fun validateFrameworkFirstJar(mergedAndroidJar: Path): List<String> {
        val failures = mutableListOf<String>()
        JarFile(mergedAndroidJar.toFile()).use { jarFile ->
            validateMethod(
                jarFile = jarFile,
                classEntry = "android/widget/TextView.class",
                methodName = "setText",
                methodDesc = "(Ljava/lang/CharSequence;)V",
                failures = failures,
            )
            validateMethod(
                jarFile = jarFile,
                classEntry = "android/text/TextUtils.class",
                methodName = "isEmpty",
                methodDesc = "(Ljava/lang/CharSequence;)Z",
                failures = failures,
            )
            validateField(
                jarFile = jarFile,
                classEntry = "android/provider/Settings.class",
                fieldName = "ACTION_SETTINGS",
                fieldDesc = "Ljava/lang/String;",
                failures = failures,
            )
            validateClass(jarFile, "android/view/View.class", failures)
            validateClass(jarFile, "android/service/notification/ZenPolicy.class", failures)
        }
        return failures
    }

    private fun validateClass(
        jarFile: JarFile,
        classEntry: String,
        failures: MutableList<String>,
    ) {
        if (jarFile.getJarEntry(classEntry) == null) {
            failures += "$classEntry missing"
        }
    }

    private fun validateMethod(
        jarFile: JarFile,
        classEntry: String,
        methodName: String,
        methodDesc: String,
        failures: MutableList<String>,
    ) {
        val classNode = loadClassNode(jarFile, classEntry)
        val hasMethod = classNode?.methods?.any { method ->
            method.name == methodName && method.desc == methodDesc
        } == true
        if (!hasMethod) {
            failures += "$classEntry missing $methodName$methodDesc"
        }
    }

    private fun validateField(
        jarFile: JarFile,
        classEntry: String,
        fieldName: String,
        fieldDesc: String,
        failures: MutableList<String>,
    ) {
        val classNode = loadClassNode(jarFile, classEntry)
        val hasField = classNode?.fields?.any { field ->
            field.name == fieldName && field.desc == fieldDesc
        } == true
        if (!hasField) {
            failures += "$classEntry missing $fieldName:$fieldDesc"
        }
    }

    private fun loadClassNode(
        jarFile: JarFile,
        classEntry: String,
    ): ClassNode? {
        val entry = jarFile.getJarEntry(classEntry) ?: return null
        return jarFile.getInputStream(entry).use { input ->
            ClassNode().also { node ->
                ClassReader(input.readBytes()).accept(node, 0)
            }
        }
    }
}
