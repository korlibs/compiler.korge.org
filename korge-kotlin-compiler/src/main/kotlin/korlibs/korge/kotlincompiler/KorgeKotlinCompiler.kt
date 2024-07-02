@file:OptIn(ExperimentalBuildToolsApi::class)

package korlibs.korge.kotlincompiler

import korlibs.korge.kotlincompiler.maven.*
import org.jetbrains.kotlin.buildtools.api.*
import org.jetbrains.kotlin.buildtools.api.jvm.*
import java.io.*
import java.security.*
import java.util.*
import kotlin.system.*
import korlibs.korge.kotlincompiler.module.Module
import korlibs.korge.kotlincompiler.util.*
import kotlin.time.*

// https://github.com/JetBrains/kotlin/tree/master/compiler/build-tools/kotlin-build-tools-api
// https://github.com/JetBrains/kotlin/blob/bc1ddd8205f6107c7aec87a9fb3bd7713e68902d/compiler/build-tools/kotlin-build-tools-api-tests/src/main/kotlin/compilation/model/JvmModule.kt
class KorgeKotlinCompiler(val pipes: StdPipes = StdPipes, val reload: Boolean = false, val pid: Long) {

    val reloadSocketFile = File("$TMP_DIR/korge.run.$pid.socket")
    var korgeVersion = BuildConfig.LATEST_KORGE_VERSION

    val stdout get() = pipes.out
    val stderr get() = pipes.err

    companion object {
        //@JvmStatic
        //fun main(args: Array<String>) {
        //    println("Running main...")
        //    println(JvmMeta.javaExecutablePath)
        //    val libs = setOf(
        //        MavenArtifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.0"),
        //        MavenArtifact("com.soywiz.korge", "korge-jvm", BuildConfig.LATEST_KORGE_VERSION)
        //    )
        //    val mod1 = Module(
        //        projectDir = File("C:\\Users\\soywiz\\projects\\korge-snake\\modules\\korma-tile-matching"),
        //        libs = libs,
        //        name = "korge-snake",
        //    )
        //    val snakeModule = Module(
        //        projectDir = File("C:\\Users\\soywiz\\projects\\korge-snake"),
        //        moduleDeps = setOf(mod1),
        //        libs = libs,
        //        name = "korge-snake",
        //    )
        //    val compiler = KorgeKotlinCompiler()
        //    //compileAndRun(snakeModule, mapOf("KORGE_HEADLESS" to "true", "KORGE_IPC" to "C:\\Users\\soywiz\\AppData\\Local\\Temp\\/KORGE_IPC-304208"))
        //    //repeat(3) { compiler.compileAllModules(snakeModule) }
        //    compiler.compileAndRun(snakeModule)
        //}

        private val service by lazy { CompilationService.loadImplementation(ClassLoader.getSystemClassLoader()) }
        //private val service = CompilationService.loadImplementation(KorgeKotlinCompiler::class.java.classLoader)
        //private val service = CompilationService.loadImplementation(ClassLoader.getPlatformClassLoader())
        private val executionConfig by lazy {
            service.makeCompilerExecutionStrategyConfiguration()
                .useInProcessStrategy()
        }

        fun getCompilerVersion(): String = service.getCompilerVersion()

    }

    suspend fun compileAndRun(module: Module, envs: Map<String, String> = mapOf()): Int {
        compileAllModules(module)
        return runModule(module, envs)
    }

    suspend fun compileAllModules(module: Module) {
        module.allModuleDeps.forEach {
            compileModule(it)
        }
        System.gc()
    }

    suspend fun compileModule(module: Module) {
        val srcDirs = module.srcDirs
        val resourcesDirs = module.resourceDirs
        val libFiles = arrayListOf<File>()
        val root = module.projectDir

        for (dep in module.allModuleDeps.map { it.classesDir }) libFiles += dep

        rootDir = root
        sourceDirs = srcDirs.toSet()
        libs = module.getLibsFiles(pipes).toSet() + libFiles.toSet()

        lateinit var result: CompilationResult
        val time = measureTimeMillis {
            result = compileJvm()
            //println(compiler.compileJvm(forceRecompilation = true))
        }
        if (result != CompilationResult.COMPILATION_SUCCESS) {
            buildDirectory.deleteRecursively()
            //compiler.filesTxtFile.delete() // Deletes just in case
        }
        stdout.println("$result: $time ms - ${module.projectDir}")
        //compiler.runJvm()
    }

    //fun distinctMap(src: Map<String, String>, dst: Map<String, String>): Map<String, String> {
    //    val out = LinkedHashMap<String, String>()
    //    for (key in (src.keys + dst.keys)) {
    //        if (dst[key] != src[key]) {
    //            (src[key] ?: dst[key])?.let { out[key] = it }
    //        }
    //    }
    //    return out
    //}

    suspend fun runModule(module: Module, envs: Map<String, String> = mapOf()): Int {
        val allClasspaths: Set<File> = module.allModuleDeps.flatMap { setOf(it.classesDir) + it.resourceDirs + it.getLibsFiles(pipes) }.toSet()
        //stdout.println("Running... ${module.main} extra.envs=${distinctMap(System.getenv(), envs)}")
        stdout.println("Running... ${module.main}")
        return runJvm(module.main, allClasspaths, envs)
    }

    suspend fun runJvm(main: String, classPaths: Collection<File>, envs: Map<String, String> = mapOf()): Int {
        val allArgs = buildList {
            add(JvmMeta.javaExecutablePath)

            if (reload) {
                val ARGS_SEPARATOR = "<:/:>"
                val CMD_SEPARATOR = "<@/@>"

                val params = listOf(
                    reloadSocketFile.absolutePath, // reload socket path
                    listOf<String>(), // continuous command
                    "false", // redefine classes
                    classPaths.filter { it.isDirectory }.map { it.canonicalPath }, // classpaths / class roots
                    //classPaths.map { it.canonicalPath },
                )

                val agentArgs = params.joinToString(ARGS_SEPARATOR) { if (it is List<*>) it.joinToString(CMD_SEPARATOR) else "$it" }

                add("-javaagent:${MavenArtifact("com.soywiz.korge:korge-reload-agent:${korgeVersion}").getSingleMavenArtifact(pipes).absolutePath}=$agentArgs")
            }

            val addOpens = buildList {
                add("java.desktop/sun.java2d.opengl")
                add("java.desktop/java.awt")
                add("java.desktop/sun.awt")
                if (OS.CURRENT == OS.MACOS) {
                    add("java.desktop/sun.lwawt")
                    add("java.desktop/sun.lwawt.macosx")
                    add("java.desktop/com.apple.eawt")
                    add("java.desktop/com.apple.eawt.event")
                }
                if (OS.CURRENT == OS.LINUX) {
                    add("java.desktop/sun.awt.X11")
                }
            }

            for (addOpen in addOpens) {
                add("--add-opens=$addOpen=ALL-UNNAMED")
            }

            add("-cp")
            add(classPaths.joinToString(File.pathSeparator))
            add(main)
        }
        //stdout.println("ARGS: " + allArgs.joinToString(" "))
        return ProcessBuilder(allArgs)
            .also { builder -> builder.environment()
                .also { it.clear() }
                .also {
                    it.putAll(envs + mapOf(
                        "KORGE_AUTORELOAD" to "$reload",
                        "DEBUG_KORGE_RELOAD_AGENT" to "$reload"
                    ))
                }
            }
            .startEnsuringDestroyed()
            .redirectToWaitFor(pipes)
    }

    var rootDir: File = File("/temp")
    val buildDirectory get() = File(rootDir, ".korge").absoluteFile
    val filesTxtFile get() = File(buildDirectory, "files.txt")
    val classesDir get() = File(buildDirectory, "classes").absolutePath
    var sourceDirs: Set<File> = emptySet()
    var libs: Set<File> = emptySet()
        set(value) {
            if (field != value) {
                field = value
                snapshot = null
            }
        }

    private var snapshot: ClasspathSnapshotBasedIncrementalCompilationApproachParameters? = null

    private val icWorkingDir by lazy { File(buildDirectory, "ic").also { it.mkdirs() } }
    private val icCachesDir by lazy { File(icWorkingDir, "caches").also { it.mkdirs() } }

    private fun createSnapshots(): ClasspathSnapshotBasedIncrementalCompilationApproachParameters {
        val snapshots = mutableListOf<File>()

        var savedSnapshots = 0
        val snapshotsTime = measureTime {
            for (lib in libs) {
                try {
                    val isJar = lib.isFile && lib.name.endsWith(".jar", ignoreCase = true)
                    val isDirectory = lib.isDirectory
                    if (isJar) {
                        val hexDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA1").digest(lib.readBytes()))
                        val file = File(icWorkingDir, "dep-" + lib.name + "-$hexDigest.snapshot").absoluteFile
                        if (!file.exists()) {
                            //val snapshot = service.calculateClasspathSnapshot(lib, ClassSnapshotGranularity.CLASS_MEMBER_LEVEL)
                            val snapshot = service.calculateClasspathSnapshot(lib, ClassSnapshotGranularity.CLASS_LEVEL)
                            //stdout.println("Saving... $file")
                            file.parentFile.mkdirs()
                            //println(snapshot.classSnapshots)
                            savedSnapshots++
                            snapshot.saveSnapshot(file)
                        }
                        snapshots += file
                    } else if (isDirectory) {
                        //val snapshot = service.calculateClasspathSnapshot(lib, ClassSnapshotGranularity.CLASS_MEMBER_LEVEL)
                        val snapshot = service.calculateClasspathSnapshot(lib, ClassSnapshotGranularity.CLASS_LEVEL)
                        val hash = snapshot.classSnapshots.values
                            .filterIsInstance<AccessibleClassSnapshot>()
                            .withIndex()
                            .sumOf { (index, snapshot) -> index * 31 + snapshot.classAbiHash }
                        val file = File(icWorkingDir, "dep-$hash.snapshot")
                        //if (!file.exists()) {
                            file.parentFile.mkdirs()
                            savedSnapshots++
                            snapshot.saveSnapshot(file)
                        //}
                        snapshots += file
                    }
                } catch (e: Throwable) {
                    stderr.println("ERROR generating $lib snapshot")
                    e.printStackTrace(stderr)
                }
            }
        }

        stdout.println("Saved $savedSnapshots snapshots in $snapshotsTime")

        val shrunkClasspathSnapshotFile = File(icWorkingDir, "shrunk-classpath-snapshot.bin")
        return ClasspathSnapshotBasedIncrementalCompilationApproachParameters(
            snapshots,
            //emptyList(),
            shrunkClasspathSnapshotFile
        )
    }

    fun getAllFiles(): List<File> {
        return sourceDirs.flatMap { it.walkBottomUp() }.filter { it.extension == "kt" }.map { it.absoluteFile }
    }

    fun getAllFilesToModificationTime(): Map<File, Long> {
        return getAllFiles().associateWith { it.lastModified() }
    }

    private fun saveFileToTime(files: Map<File, Long>): String {
        return files.entries.joinToString("\n") { "${it.key}:::${it.value}" }
    }

    private fun loadFileToTime(text: String): Map<File, Long> {
        return text.split("\n").filter { it.contains(":::") }.map { val (file, time) = it.split(":::"); File(file) to time.toLong() }.toMap()
    }

    fun compileJvm(forceRecompilation: Boolean = false): CompilationResult {
        buildDirectory.mkdirs()
        if (forceRecompilation) {
            filesTxtFile.delete()
        }
        val oldFiles = loadFileToTime(filesTxtFile.takeIf { it.exists() }?.readText() ?: "")
        val allFiles = getAllFilesToModificationTime()
        filesTxtFile.writeText(saveFileToTime(allFiles))
        val sourcesChanges = getModifiedFiles(oldFiles, allFiles)

        //if (sourcesChanges.removedFiles.isEmpty() && sourcesChanges.modifiedFiles.isEmpty()) {
        //    return CompilationResult.COMPILATION_SUCCESS
        //}

        if (snapshot == null) {
            snapshot = createSnapshots()
        }

        stdout.println("CHANGES for $rootDir -> MODIFIED: ${sourcesChanges.modifiedFiles.toList()}, REMOVED: ${sourcesChanges.removedFiles.toList()}")

        return service.compileJvm(
            projectId = ProjectId.ProjectUUID(UUID.randomUUID()),
            strategyConfig = executionConfig,
            compilationConfig = service.makeJvmCompilationConfiguration().also { compilationConfig ->
                compilationConfig.useLogger(object : KotlinLogger {
                    override val isDebugEnabled: Boolean get() = false

                    override fun debug(msg: String) {
                        if (verbose) stdout.println(msg)
                    }

                    override fun error(msg: String, throwable: Throwable?) {
                        stderr.println(msg)
                        throwable?.printStackTrace(stderr)
                    }

                    override fun info(msg: String) {
                        if (verbose) stdout.println(msg)
                    }

                    override fun lifecycle(msg: String) {
                        stdout.println(msg)
                    }

                    override fun warn(msg: String) {
                        stdout.println(msg)
                    }

                })
                compilationConfig.useIncrementalCompilation(
                    icCachesDir,
                    sourcesChanges,
                    snapshot!!,
                    compilationConfig.makeClasspathSnapshotBasedIncrementalCompilationConfiguration().also {
                        it.setBuildDir(buildDirectory)
                        it.setRootProjectDir(rootDir)
                        //it.forceNonIncrementalMode(true)
                    }
                )
            },
            //listOf(File("/temp/1")),
            sources = listOf<File>(
                //File("/temp/1"),
                //File("/temp/1-common")
                //File("C:\\Users\\soywiz\\projects\\korge-snake\\src"),
                //File("C:\\Users\\soywiz\\projects\\korge-snake\\modules\\korma-tile-matching\\src\\commonMain\\kotlin"),
            ) + allFiles.map { it.key },
            //listOf(File("/temp/1-common")),
            arguments = listOf(
                "-module-name=${rootDir.name}",
                "-Xjdk-release=21",
                //"-Xuse-fast-jar-file-system",
                "-jvm-target=21",
                "-Xmulti-platform",
                "-Xlambdas=indy",
                //"-progressive",
                "-language-version=2.0",
                "-api-version=2.0",
                "-no-stdlib",
                "-no-reflect",
                "-Xexpect-actual-classes",
                "-Xenable-incremental-compilation",
                "-classpath=${libs.joinToString(File.pathSeparator) { it.absolutePath }}",
                "-d",
                classesDir,
                //add("-Xfriend-paths=${friendPaths.joinToString(",")}")
                //"C:\\Users\\soywiz\\projects\\korge-snake\\src",
                //"C:\\Users\\soywiz\\projects\\korge-snake\\modules\\korma-tile-matching\\src\\commonMain\\kotlin",
            )
        )
    }

    fun getModifiedFiles(old: Map<File, Long>, new: Map<File, Long>): SourcesChanges.Known {
        val modified = arrayListOf<File>()
        val removed = arrayListOf<File>()
        for ((file, newTime) in new) {
            if (old[file] != newTime) {
                modified += file
            }
        }
        for ((file, oldTime) in old) {
            if (file !in new) {
                removed += file
            }
        }
        return SourcesChanges.Known(modified, removed)
    }
}
