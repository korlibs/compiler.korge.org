package korlibs.korge.kotlincompiler.module

import java.io.*

data class Module(
    val projectDir: File,
    val moduleDeps: Set<Module> = setOf(),
    val libs: Set<File> = setOf(),
    val main: String = "MainKt",
) {
    val allModuleDeps: Set<Module> by lazy {
        (moduleDeps.flatMap { it.allModuleDeps + it } + this).toSet()
    }
    val allTransitiveLibs by lazy { allModuleDeps.flatMap { it.libs }.toSet() }

    val buildDir = File(projectDir, ".korge")
    val classesDir = File(buildDir, "classes")
    val srcDirs by lazy {
        val dirs = arrayListOf<File>()
        if (File(projectDir, "src/commonMain/kotlin").isDirectory) {
            dirs += File(projectDir, "src/commonMain/kotlin")
            dirs += File(projectDir, "src/jvmMain/kotlin")
        } else {
            dirs += File(projectDir, "src")
            dirs += File(projectDir, "src@jvm")
        }
        dirs
    }
    val resourceDirs by lazy {
        val dirs = arrayListOf<File>()
        if (File(projectDir, "src/commonMain/kotlin").isDirectory) {
            dirs += File(projectDir, "src/commonMain/resources")
            dirs += File(projectDir, "src/jvmMain/resources")
        } else {
            dirs += File(projectDir, "resources")
            dirs += File(projectDir, "resources@jvm")
        }
        dirs
    }
}
