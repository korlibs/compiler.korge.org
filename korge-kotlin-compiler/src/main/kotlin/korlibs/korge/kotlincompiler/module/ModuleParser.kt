package korlibs.korge.kotlincompiler.module

import korlibs.io.dynamic.*
import korlibs.io.serialization.yaml.*
import korlibs.korge.kotlincompiler.git.*
import korlibs.korge.kotlincompiler.maven.*
import korlibs.korge.kotlincompiler.util.*
import java.io.*

open class ProjectParser(val projectDir: File) {
    private val modules = LinkedHashMap<File, ModuleParser>()

    val allModules get() = modules.values.toList()

    val rootModule by lazy { module(projectDir) }

    open fun artifactTransformer(artifact: MavenArtifact): MavenArtifact {
        if (artifact.version == "") {
            return artifact.copy(version = "999.0.0.999")
        }
        return artifact
    }
    open fun artifactTransformer(artifact: MavenDependency): MavenDependency {
        return artifact.copy(artifact = artifactTransformer(artifact.artifact))
    }

    fun module(moduleDir: File): ModuleParser = modules.getOrPut(moduleDir.canonicalFile) {
        ModuleParser(this, moduleDir.canonicalFile).also { it.parse() }
    }
}

class ModuleParser(val rootProject: ProjectParser, val moduleDir: File) {
    override fun toString(): String = "ModuleParser($moduleDir, name=$moduleName, version=$korgeVersion)"

    var korgeVersion: String? = null
    var moduleName: String = moduleDir.name

    val moduleDeps = mutableSetOf<ModuleParser>()
    val mavenDeps = mutableSetOf<MavenDependency>()


    val module: Module by lazy {
        Module(
            projectDir = moduleDir,
            //name = moduleName,
            moduleDeps = moduleDeps.map { it.module }.toSet(),
            libs = mavenDeps.map { it.artifact }.toSet(),
        )
    }


    fun parse() {
        check(moduleDir.exists()) { "'${moduleDir.absolutePath}' doesn't exists"}
        File(moduleDir, "gradle/libs.versions.toml").takeIfExists()?.let { tryParseLibsVersionsToml(it) }
        File(moduleDir, "build.gradle.kts").takeIfExists()?.let { parseBuildGradleKts(it) }
        File(moduleDir, "deps.kproject.yml").takeIfExists()?.let { parseKProjectYml(it) }
        File(moduleDir, "kproject.yml").takeIfExists()?.let { parseKProjectYml(it) }
        File(moduleDir, "module.yaml").takeIfExists()?.let { parseModuleYml(it) }
        File(moduleDir, "project.yaml").takeIfExists()?.let { parseProjectYml(it) }
        File(moduleDir, "korge.yml").takeIfExists()?.let { parseKorgeYml(it) }
    }

    fun tryParseLibsVersionsToml(file: File) {
        for (line in file.readLines()) {
            Regex("^korge\\s*=.*").find(line)?.let {
                val version = Regex("version\\s*=\\s*\"(.*?)\"").find(it.groupValues[0])?.groupValues?.get(1) ?: error("Can't find korge version")
                korgeVersion = version
            }
        }
    }

    fun parseDependency(depStr: String) {
        when {
            depStr.startsWith("https://") -> {
                // parse this url into owner, repo name, tag, path, and extra hash
                //   https://github.com/korlibs/korge-ext/tree/v0.1.3/korma-tile-matching/korma-tile-matching##2a331af4bab999c105c5f945b152e5f95dbf49f1
                Regex("https://github.com/([^/]+)/([^/]+)/tree/([^/]+)/([^#]+)(##(.*))?").matchEntire(depStr)?.let { match ->
                    val owner = match.groupValues[1]
                    val repo = match.groupValues[2]
                    val ref = match.groupValues[3]
                    val path = match.groupValues[4]
                    val hash = match.groupValues[6]

                    val moduleDir = File(rootProject.projectDir, "modules/${File(path).name}")
                    val gitArchiveFile = File(moduleDir, ".gitarchive")
                    if (!gitArchiveFile.exists() || gitArchiveFile.readText().trim() != ref) {
                        gitArchiveFile.writeText(ref)
                        GitHubRepoRefPath(owner, repo, hash.takeIf { it.isNotEmpty() } ?: ref, path).extractTo(moduleDir)
                    }
                    moduleDeps += rootProject.module(moduleDir)
                }
                //println("DEP: $depStr")
            }
            depStr.contains(":") -> {
                val mavenBase = depStr.removePrefix("maven::")
                val sourceSet = mavenBase.substringBefore("::", "")
                val parts = mavenBase.substringAfter("::").split(":")
                val mavenDep = rootProject.artifactTransformer(MavenDependency(MavenArtifact(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "", parts.getOrNull(2) ?: ""), sourceSet = sourceSet))
                //println("MAVEN: $mavenDep")
                mavenDeps += mavenDep
            }
            else -> {
                // Url or file path
            }
        }
    }

    fun parseCommonYaml(file: File) {
        //println("parseCommonYaml: $file")
        val root = Yaml.decode(file.readText()).dyn
        root["name"].toStringOrNull()?.let { moduleName = it }

        for (dep in root["dependencies"].list) {
            parseDependency(dep.str)
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
