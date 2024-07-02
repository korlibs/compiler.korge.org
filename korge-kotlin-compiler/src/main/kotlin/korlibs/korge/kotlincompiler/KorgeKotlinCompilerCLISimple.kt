@file:OptIn(ExperimentalStdlibApi::class)

package korlibs.korge.kotlincompiler

import korlibs.io.serialization.json.*
import korlibs.korge.kotlincompiler.module.*
import korlibs.korge.kotlincompiler.util.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import java.io.*
import java.lang.management.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.security.*
import kotlin.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class KorgeKotlinCompilerCLISimple(val currentDir: File, val pipes: StdPipes) {
    val out get() = pipes.out
    val err get() = pipes.err

    //companion object {
    //    @JvmStatic
    //    fun main(args: Array<String>) {
    //        KorgeKotlinCompilerCLISimple(File(".").canonicalFile, StdPipes).main(args)
    //    }
    //}

    fun file(path: String): File = File(path).takeIf { it.isAbsolute } ?: File(currentDir, path)
    fun file(base: String, path: String): File = file("$base/$path")
    fun file(base: File, path: String): File = File(base, path)

    fun main(args: Array<String>) {
        runBlocking {
            suspendMain(args, System.getenv(), pid = 0L)
        }
    }

    private fun CLIProcessor.updateWrapper(it: ArrayDeque<String>) {
        val version = it.removeFirstOrNull() ?: error("version not specified")
        //KorgeKotlinCompiler.compileModule()
        out.println("Updating to... $version")

        val downloadUrl = "https://github.com/korlibs/compiler.korge.org/releases/download/v$version/korge-kotlin-compiler-all.tar.xz"
        out.println("URL: $downloadUrl")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(URL(downloadUrl).readBytes()).toHexString().lowercase()
        out.println("SHA256: $sha256")


        //https://github.com/korlibs/compiler.korge.org/releases/download/v%INSTALLER_VERSION%/korge-kotlin-compiler-all.tar.xz

        fun String.replaceVersion(): String {
            return this
                .replace(Regex("INSTALLER_VERSION=.*")) { "INSTALLER_VERSION=$version" }
                .replace(Regex("INSTALLER_SHA256=.*")) { "INSTALLER_SHA256=$sha256" }
        }

        file("korge").takeIfExists()?.let { it.writeText(it.readText().replaceVersion()) }
        file("korge.bat").takeIfExists()?.let { it.writeText(it.readText().replaceVersion()) }
    }

    suspend fun suspendMain(args: Array<String>, envs: Map<String, String>, pid: Long, checkAlive: () -> Boolean = { true }) {
        println("KorgeKotlinCompilerCLISimple.main: ${args.toList()}, envs=${envs.keys}, stdout=$out, stderr=$err")

        val processor = CLIProcessor("KorGE Kotlin Compiler & Tools", BuildConfig.KORGE_COMPILER_VERSION, pipes)
            .registerCommand("install", "uninstall", "forge", desc = "Opens the KorGE Forge installer") { forgeInstaller() }
            .registerCommand("open", desc = "Opens the project with KorGE Forge") { openInIde(file(it.removeFirstOrNull() ?: ".")) }
            .registerCommand("version", desc = "Displays versions") {
                out.println(
                    """
                        {
                            "tool": "${BuildConfig.KORGE_COMPILER_VERSION}",
                            "jvm": "${ManagementFactory.getRuntimeMXBean().vmName} ${ManagementFactory.getRuntimeMXBean().vmVendor} ${ManagementFactory.getRuntimeMXBean().vmVersion}",
                            "korge": "${BuildConfig.LATEST_KORGE_VERSION}",
                            "kotlin": "${KorgeKotlinCompiler.getCompilerVersion()}"
                        }
                    """.trimIndent()
                )
            }
            .registerCommand("info", desc = "Display project information in JSON format") {
                val path = it.removeFirstOrNull() ?: "."

                val projectParser = ProjectParser(file(path), pipes)
                val rootModuleParser = projectParser.rootModule
                val rootModule = rootModuleParser.module


                //@Serializable
                //class ProjectInfo(val rootDir: String, val projects: Map<String, ModuleInfo>)

                out.println(Json.stringify(buildMap<String, Any> {
                    put("model.version", BuildConfig.KORGE_COMPILER_VERSION)
                    put("root", rootModule.name)
                    put("targets", buildMap {
                        put("common", listOf())
                        put("concurrent", listOf("common"))
                        put("jvm", listOf("concurrent"))
                        put("android", listOf("concurrent"))
                        put("jvmAndAndroid", listOf("jvm", "android"))
                        put("js", listOf("common"))
                        put("wasm", listOf("common"))
                        put("native", listOf("concurrent"))
                        put("apple", listOf("native"))
                        put("ios", listOf("apple"))
                        put("tvos", listOf("apple"))
                    })
                    put("projects", buildMap<String, Any> {
                        for (module in rootModule.allModuleDeps) {
                            put(module.name, buildMap<String, Any> {
                                put("name", module.name)
                                put("path", module.projectDir.canonicalPath)
                                put("main", module.main)
                                put("resources", buildMap {
                                    put("common", listOf("resources"))
                                })
                                put("sources", buildMap {
                                    put("common", listOf("src"))
                                    put("jvm", listOf("src@jvm"))
                                })
                                put("tests", buildMap {
                                    put("common", listOf("test"))
                                    put("jvm", listOf("test@jvm"))
                                })
                                put("modules", module.moduleDeps.map { it.name })
                                put("maven", module.libs.map { it.fqName })
                                put("libs", buildMap {
                                    put("jvm", module.getLibsFiles(pipes).map { it.absolutePath })
                                })
                            })
                        }
                    })
                }, pretty = true))
            }
            //.registerCommand("idea", desc = "Creates IDEA modules") { }
            .registerCommand("build", desc = "Builds the specified <folder> containing a KorGE project") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                KorgeKotlinCompiler(pipes, pid = pid).compileAllModules(
                    ProjectParser(file(path), pipes).rootModule.module,
                )
            }
            .registerCommand("build:watch", desc = "Builds the specified <folder> containing a KorGE project watching for changes") {
                val path = it.removeFirstOrNull() ?: "."
                buildWatch(path, pid) { checkAlive() }
            }
            //.registerCommand("test", desc = "Test the specified <folder> containing a KorGE project") {
            //    val path = it.removeFirstOrNull() ?: "."
            //    TODO()
            //}
            .registerCommand("clean", desc = "Removes all the build caches") {
                val path = it.removeFirstOrNull() ?: "."
                val projectDir = file(path)

                out.println("Deleting for $projectDir...")
                for (module in ProjectParser(projectDir, pipes).rootModule.module.allModuleDeps) {
                    val dotKorgeFile = File(module.projectDir, ".korge")
                    out.println("Deleting $dotKorgeFile...")
                    dotKorgeFile.deleteRecursively()
                }
                //KorgeKotlinCompiler.compileModule()
            }
            //.registerCommand("new", desc = "Creates a new KorGE project in the specified <folder>") {
            //    val path = it.removeFirstOrNull() ?: "."
            //    //KorgeKotlinCompiler.compileModule()
            //    TODO()
            //}
            .registerCommand("warm", desc = "Performs a warming-up of the daemon") {
                repeat(2) {
                    val tempFile = File.createTempFile("temp", "kotlin-korge-kotlin-compiler-warmer")
                    tempFile.delete()
                    tempFile.mkdirs()

                    file(tempFile, "module.yaml").also { it.parentFile.mkdirs() }.writeText("dependencies:")
                    file(tempFile, "src/main.kt").also { it.parentFile.mkdirs() }.writeText(
                        //language=kotlin
                        """
                    fun main() { println("Hello, World! ${'$'}{Demo.DEMO}") }
                    interface MyInt {
                        fun test() = 10
                        companion object : MyInt
                    } 
                    open class Test : MyInt { }
                    class Demo : Test(), MyInt by MyInt {
                        override fun test() = 20 
                        companion object {
                            val DEMO get() = 10.demo
                            val Int.demo get() = this + 1
                        }
                    }
                """.trimIndent()
                    )

                    repeat(2) {
                        KorgeKotlinCompiler(pipes, pid = pid).compileAllModules(
                            ProjectParser(tempFile, pipes).rootModule.module,
                        )
                    }
                }
                //tempFile.writeText("fun main() { println(\"Hello, World!\") }")
                //KorgeKotlinCompiler().also {
                //    it.sourceDirs
                //}.compileJvm()
                //tempFile.delete()
                //System.getProperty("")
                //val path = it.removeFirstOrNull() ?: "."
                ////KorgeKotlinCompiler.compileModule()
                //KorgeKotlinCompiler(stdout, stderr).compileAndRun(
                //    ProjectParser(File(path)).rootModule.module,
                //)
            }
            .registerCommand("run", desc = "Builds and runs the specified <folder> containing a KorGE project") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                var exitCode: Int = -1
                try {
                    exitCode = KorgeKotlinCompiler(pipes, pid = pid).compileAndRun(
                        ProjectParser(file(path), pipes).rootModule.module,
                        envs = envs
                    )
                } finally {
                    out.println("Run completed exitCode=$exitCode")
                }
            }
            .registerCommand("run:reload", desc = "Builds and runs the specified <folder> with hot reloading support") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                val compiler = KorgeKotlinCompiler(pipes, reload = true, pid = pid)

                val jobWatch = CoroutineScope(coroutineContext).launch {
                    try {
                        buildWatch(path, pid, first = false, onRecompile = { start, end ->
                            try {
                                out.println("Connecting to ${compiler.reloadSocketFile.absolutePath} to notify reload... start=$start, end=$end")
                                SocketChannel.open(UnixDomainSocketAddress.of(compiler.reloadSocketFile.absolutePath))
                                    .also { it.write(ByteBuffer.allocate(16).putLong(start).putLong(end).flip()) }
                                //.close()
                            } catch (e: Throwable) {
                                e.printStackTrace()
                            }
                        }) { checkAlive() }
                    } finally {
                        out.println("Cancelled directory watching")
                    }
                }

                val project = ProjectParser(file(path), pipes)
                compiler.korgeVersion = project.korgeVersion

                var exitCode: Int = -1

                try {
                    exitCode = compiler.compileAndRun(project.rootModule.module, envs = envs)
                } finally {
                    out.println("Run completed exitCode=$exitCode")
                    jobWatch.cancel()
                    jobWatch.join()
                    //delay(50.milliseconds)
                }
            }
            .registerCommand("debug", desc = "Prints debug information") {
                out.println("THREADS: ${Thread.getAllStackTraces().size}")
                for ((thread, stacktrace) in Thread.getAllStackTraces()) {
                    //out.println("")
                    out.println("Thread: $thread")
                    //out.println("  ${stacktrace.toList().joinToString("\n  ")}")
                }
                //Thread.getAllStackTraces().keys
            }
            /*

            .registerCommand("package:js", desc = "Creates a package for JavaScript (JS)") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
            }
            .registerCommand("package:wasm", desc = "Creates a package for WebAssembly (WASM)") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
            }
            .registerCommand("package:windows", desc = "Creates a package for Windows (EXE)") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
            }
            .registerCommand("package:linux", desc = "Creates a package for Linux (APP)") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
            }
            .registerCommand("package:mac", desc = "Creates a package for macOS (APP)") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
            }
            .registerCommand("package:jvm", desc = "Creates a package for the JVM (JAR)") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
            }
            .registerCommand("package:android", desc = "Creates a package for Android (APK)") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
            }
            .registerCommand("package:ios", desc = "Creates a package for iOS (IPA)") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
            }
             */
            .registerCommand("wrapper", desc = "Update wrapper to version <version>") { updateWrapper(it) }
            .registerCommand("stop", desc = "Stops the daemon") { throw ExitProcessException(0) }
            .registerCommand("gc", desc = "Triggers a GC in the daemon") { System.gc() }

        processor.process(args)
    }

    private suspend fun openInIde(projectPath: File) {
        while (true) {
            val projectPath = projectPath.canonicalFile
            val baseFolder = when (OS.CURRENT) {
                OS.WINDOWS -> File(System.getenv("LOCALAPPDATA"), "KorGEForge")
                OS.LINUX -> File(USER_HOME, ".local/share/KorGEForge")
                OS.MACOS -> File(USER_HOME, "Applications")
            }
            val exePath = when (OS.CURRENT) {
                OS.WINDOWS -> "bin/korge64.exe"
                OS.LINUX -> "bin/korge.sh"
                OS.MACOS -> "Contents/MacOS/korge"
            }

            val files = baseFolder.files()
            val korgeRunners = files.mapNotNull { File(it, exePath).takeIfExists() }
            val exe = korgeRunners.firstOrNull()
            if (exe == null) {
                err.println("KorGE Forge not installed, opening installer...")
                forgeInstaller("--install")
                err.println("Retrying opening...")
                continue
            }
            out.println("Opening $projectPath with $exe")
            ProcessBuilder(buildList {
                if (OS.CURRENT == OS.LINUX) add("sh")
                add(exe.absolutePath)
                add(projectPath.absolutePath)
            }).startDetached()
            return
        }
    }

    private suspend fun buildWatch(path: String, pid: Long, first: Boolean = true, onRecompile: (start: Long, end: Long) -> Unit = { start, end -> }, checkAlive: () -> Boolean) {
        //KorgeKotlinCompiler.compileModule()
        val parsed = ProjectParser(file(path), pipes)

        pipes.out.println("parsed.allModules: ${parsed.allModules}")
        val allSrcDirs = parsed.allModules.flatMap { it.module.srcDirs.filter { it.isDirectory } }.toSet()
        pipes.out.println("parsed.allModules.srcDirs: $allSrcDirs")

        suspend fun recompile() {
            pipes.out.println("Recompiling...")
            try {
                val start = System.currentTimeMillis()
                KorgeKotlinCompiler(pipes, pid = pid).compileAllModules(
                    ProjectParser(file(path), pipes).rootModule.module,
                )
                val end = System.currentTimeMillis()
                onRecompile(start, end)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        val modified = atomic(if (first) 1 else 0)
        val watcher = RecursiveDirectoryWatcher.watch(allSrcDirs, pipes) {
            pipes.out.println("Modified: $it")
            modified.incrementAndGet()
        }

        try {
            while (checkAlive()) {
                //out.println("ALIVE")
                if (modified.value > 0) {
                    modified.value = 0
                    recompile()
                }
                delay(50.milliseconds)
            }
        } finally {
            watcher.close()
            //out.println("CLOSED")
        }
    }

    private suspend fun forgeInstaller(vararg args: String) {
        when (OS.CURRENT) {
            OS.WINDOWS -> {
                val dir = file(USER_HOME, "Downloads").takeIf { it.isDirectory } ?: KORGE_DIR
                val data = URL("https://forge.korge.org/install-korge-forge.cmd").readBytes()
                file(dir, "install-korge-forge.cmd").writeBytes(data)
                ProcessBuilder()
                    .command("cmd", "/c", "install-korge-forge.cmd", *args)
                    .directory(dir)
                    .startDetached()
                    .redirectToWaitFor(pipes)
            }

            else -> {
                val dir = File(USER_HOME, "Downloads").takeIf { it.isDirectory } ?: KORGE_DIR
                val data = URL("https://forge.korge.org/install-korge-forge.sh").readBytes()
                File(dir, "install-korge-forge.sh").writeBytes(data)
                ProcessBuilder()
                    .command("sh", "install-korge-forge.sh", *args)
                    .directory(dir)
                    .startDetached()
                    .redirectToWaitFor(pipes)
            }
        }
    }
}