package korlibs.korge.kotlincompiler.module

import korlibs.korge.kotlincompiler.maven.*
import korlibs.korge.kotlincompiler.util.*
import java.io.*

data class Module(
    val projectDir: File,
    val moduleDeps: Set<Module> = setOf(),
    val libs: Set<MavenArtifact> = setOf(),
    val main: String = "MainKt",
    //val name: String,
    val name: String = projectDir.name,
) {
    val allModuleDeps: Set<Module> by lazy {
        (moduleDeps.flatMap { it.allModuleDeps + it } + this).toSet()
    }
    val allTransitiveLibs by lazy { allModuleDeps.flatMap { it.libs }.toSet() }

    private var _libsFiles: Set<File>? = null

    fun getLibsFiles(pipes: StdPipes): Set<File> {
        if (_libsFiles == null) _libsFiles = libs.flatMap { it.getMavenArtifacts(pipes) }.toSet()
        return _libsFiles!!
    }

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
