package korlibs.korge.kotlincompiler.module

import korlibs.io.dynamic.*
import korlibs.io.serialization.yaml.*
import korlibs.korge.kotlincompiler.*
import korlibs.korge.kotlincompiler.BuildConfig
import korlibs.korge.kotlincompiler.git.*
import korlibs.korge.kotlincompiler.maven.*
import korlibs.korge.kotlincompiler.util.*
import kotlinx.coroutines.*
import java.io.*

open class ProjectParser(val projectDir: File, val pipes: StdPipes) {
    private val modules = LinkedHashMap<File, ModuleParser>()

    private val _allModules get() = modules.values.toList()

    val rootModule by lazy { module(projectDir) }

    val allModules: List<ModuleParser> get() = rootModule.let { _allModules }

    val kotlinVersion get() = "2.0.0"
    val korgeVersion by lazy { allModules.firstNotNullOfOrNull { it.korgeVersion } ?: BuildConfig.LATEST_KORGE_VERSION }

    open fun artifactTransformer(artifact: MavenArtifact): MavenArtifact {
        var artifact = artifact
        if (artifact.group == "com.soywiz.korlibs.korge2" && artifact.name == "korge") {
            artifact = artifact.copy(group = "com.soywiz.korge", name = "korge")
        }

        if (artifact.version == "") {
            return artifact.copy(version = BuildConfig.LATEST_KORGE_VERSION)
        }
        return artifact
    }
    open fun artifactTransformer(artifact: MavenDependency): MavenDependency {
        return artifact.copy(artifact = artifactTransformer(artifact.artifact))
    }

    fun module(moduleDir: File): ModuleParser = modules.getOrPut(moduleDir.canonicalFile) {
        runBlocking {
            ModuleParser(this@ProjectParser, moduleDir.canonicalFile).also { it.parse() }
        }
    }
}

class ModuleParser(val rootProject: ProjectParser, val moduleDir: File) {
    val pipes get() = rootProject.pipes
    override fun toString(): String = "ModuleParser($moduleDir, name=$moduleName, version=$korgeVersion)"

    var korgeVersion: String? = null
    var moduleName: String = moduleDir.name

    val moduleDeps = mutableSetOf<ModuleParser>()
    val mavenDeps = mutableSetOf<MavenDependency>()

    val module: Module by lazy {
        Module(
            projectDir = moduleDir,
            name = moduleName,
            moduleDeps = moduleDeps.map { it.module }.toSet(),
            libs = (
                listOf(
                    MavenArtifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.0"),
                    MavenArtifact("com.soywiz.korge", "korge-jvm", rootProject.korgeVersion),
                ) + mavenDeps.map { it.artifact }
            ).toSet(),
        )
    }


    suspend fun parse(): Boolean {
        check(moduleDir.exists()) { "'${moduleDir.absolutePath}' doesn't exists"}
        var contributions = 0
        File(moduleDir, "gradle/libs.versions.toml").takeIfExists()?.let { if (tryParseLibsVersionsToml(it)) contributions++ }
        File(moduleDir, "build.gradle.kts").takeIfExists()?.let { if (parseBuildGradleKts(it)) contributions++ }
        File(moduleDir, "deps.kproject.yml").takeIfExists()?.let { if (parseKProjectYml(it)) contributions++ }
        File(moduleDir, "kproject.yml").takeIfExists()?.let { if (parseKProjectYml(it)) contributions++ }
        File(moduleDir, "module.yaml").takeIfExists()?.let { if (parseModuleYml(it)) contributions++ }
        File(moduleDir, "project.yaml").takeIfExists()?.let { if (parseProjectYml(it)) contributions++ }
        File(moduleDir, "korge.yml").takeIfExists()?.let { if (parseKorgeYml(it)) contributions++ }
        if (contributions == 0) error("Not a KorGE Module : $moduleDir")
        return contributions > 0
    }

    fun tryParseLibsVersionsToml(file: File): Boolean {
        for (line in file.readLines()) {
            Regex("^korge\\s*=.*").find(line)?.let {
                val version = Regex("version\\s*=\\s*\"(.*?)\"").find(it.groupValues[0])?.groupValues?.get(1) ?: error("Can't find korge version")
                korgeVersion = version
                return true
            }
        }
        return false
    }

    suspend fun parseDependency(depStr: String): Boolean {
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
                        gitArchiveFile.parentFile.mkdirs()
                        gitArchiveFile.writeText(ref)
                        GitHubRepoRefPath(owner, repo, hash.takeIf { it.isNotEmpty() } ?: ref, path).extractTo(moduleDir, pipes)
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
        return true
    }

    suspend fun parseCommonYaml(file: File): Boolean {
        //println("parseCommonYaml: $file")
        val root = Yaml.decode(file.readText()).dyn
        root["name"].toStringOrNull()?.let { moduleName = it }
        root["korge_version"].toStringOrNull()?.let { korgeVersion = it }

        for (dep in root["dependencies"].list) {
            parseDependency(dep.str)
        }
        return true
    }

    suspend fun parseKProjectYml(file: File): Boolean = parseCommonYaml(file)

    suspend fun parseKorgeYml(file: File): Boolean = parseCommonYaml(file)

    // AMPER root project
    suspend fun parseProjectYml(file: File): Boolean = parseCommonYaml(file)

    // AMPER module
    suspend fun parseModuleYml(file: File): Boolean = parseCommonYaml(file)

    // Best effort without executing code
    suspend fun parseBuildGradleKts(file: File): Boolean {
        var korgeBlockOnce = false
        var korgeBlock = false
        for (line in file.readLines()) {
            val tline = line.trim()
            when {
                tline.startsWith("korge {") -> {
                    korgeBlock = true
                    korgeBlockOnce = true
                }
                tline.startsWith("}") -> korgeBlock = false
                korgeBlock -> {
                    // id = ""
                }
                else -> Unit // Do nothing
            }
        }
        return korgeBlockOnce
    }
}
