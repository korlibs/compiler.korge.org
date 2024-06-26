package korlibs.korge.kotlincompiler.module

import korlibs.io.dynamic.*
import korlibs.io.serialization.yaml.*
import korlibs.korge.kotlincompiler.maven.*
import korlibs.korge.kotlincompiler.util.*
import java.io.*

class ModuleParser(val projectDir: File) {
    var korgeVersion: String? = null
    var moduleName: String? = null

    val mavenDeps = mutableSetOf<MavenDependency>()

    fun parse() {
        check(projectDir.exists()) { "'${projectDir.absolutePath}' doesn't exists"}
        File(projectDir, "gradle/libs.versions.toml").takeIfExists()?.let { tryParseLibsVersionsToml(it) }
        File(projectDir, "build.gradle.kts").takeIfExists()?.let { parseBuildGradleKts(it) }
        File(projectDir, "deps.kproject.yml").takeIfExists()?.let { parseKProjectYml(it) }
        File(projectDir, "kproject.yml").takeIfExists()?.let { parseKProjectYml(it) }
        File(projectDir, "module.yaml").takeIfExists()?.let { parseModuleYml(it) }
        File(projectDir, "project.yaml").takeIfExists()?.let { parseProjectYml(it) }
        File(projectDir, "korge.yml").takeIfExists()?.let { parseKorgeYml(it) }
    }

    fun tryParseLibsVersionsToml(file: File) {
        for (line in file.readLines()) {
            Regex("^korge\\s*=.*").find(line)?.let {
                val version = Regex("version\\s*=\\s*\"(.*?)\"").find(it.groupValues[0])?.groupValues?.get(1) ?: error("Can't find korge version")
                korgeVersion = version
            }
        }
    }

    fun parseCommonYaml(file: File) {
        //println("parseCommonYaml: $file")
        val root = Yaml.decode(file.readText()).dyn
        root["name"].toStringOrNull()?.let { moduleName = it }

        for (dep in root["dependencies"].list) {
            val depStr = dep.str
            when {
                depStr.contains(":") -> {
                    val mavenBase = depStr.removePrefix("maven::")
                    val sourceSet = mavenBase.substringBefore("::", "")
                    val parts = mavenBase.substringAfter("::").split(":")
                    val mavenDep = MavenDependency(MavenArtifact(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "", parts.getOrNull(2) ?: ""), sourceSet = sourceSet)
                    //println("MAVEN: $mavenDep")
                    mavenDeps += mavenDep
                }
                else -> {
                    // Url or file path
                }
            }
        }
    }

    fun parseKProjectYml(file: File) {
        parseCommonYaml(file)
    }

    fun parseKorgeYml(file: File) {
        parseCommonYaml(file)
    }

    // AMPER root project
    fun parseProjectYml(file: File) {
        parseCommonYaml(file)
    }

    // AMPER module
    fun parseModuleYml(file: File) {
        parseCommonYaml(file)
    }

    // Best effort without executing code
    fun parseBuildGradleKts(file: File) {
        var korgeBlock = false
        for (line in file.readLines()) {
            val tline = line.trim()
            when {
                tline.startsWith("korge {") -> korgeBlock = true
                tline.startsWith("}") -> korgeBlock = false
                korgeBlock -> {
                    // id = ""
                }
                else -> Unit // Do nothing
            }
        }
    }
}
