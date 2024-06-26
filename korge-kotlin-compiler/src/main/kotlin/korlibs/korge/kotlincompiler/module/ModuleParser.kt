package korlibs.korge.kotlincompiler.module

import korlibs.io.dynamic.*
import korlibs.io.serialization.yaml.*
import korlibs.korge.kotlincompiler.maven.*
import java.io.*

class ModuleParser {
    var moduleName: String? = null

    val mavenDeps = arrayListOf<MavenDependency>()

    fun parse(projectDir: File) {
        val root = Yaml.decode(projectDir.readText()).dyn
        for (dep in root["dependencies"].list) {
            val depStr = dep.str
            when {
                depStr.contains(":") -> {
                    val parts = depStr.split(":")
                    mavenDeps += MavenDependency(MavenArtifact(parts[0], parts[1], parts[2]))
                }
                else -> {
                    // Url or file path
                }
            }
        }
    }

    fun parseLibsVersionsToml() {
    }

    fun parseKProjectYml() {
    }

    fun parseKorgeYml() {
    }

    fun parseModuleYml() {
    }

    // Best effort without executing code
    fun parseBuildGradleKts() {
    }
}
