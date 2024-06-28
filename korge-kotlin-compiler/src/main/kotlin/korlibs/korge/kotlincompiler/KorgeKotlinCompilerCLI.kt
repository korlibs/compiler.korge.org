@file:OptIn(ExperimentalStdlibApi::class)

package korlibs.korge.kotlincompiler

import korlibs.korge.kotlincompiler.module.*
import korlibs.korge.kotlincompiler.socket.*
import korlibs.korge.kotlincompiler.util.*
import java.io.*
import java.lang.management.*
import java.net.*
import java.nio.channels.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.*
import kotlin.system.*

// taskkill /F /IM java.exe /T

val verbose by lazy { System.getenv("KORGE_VERBOSE") == "true" }
//val verbose = true
//val restartDaemon = false
//val restartDaemon = System.getenv("KORGE_DAEMON_RESTART_ALWAYS") == "true"

val USER_HOME by lazy { File(System.getProperty("user.home")) }
val KORGE_DIR by lazy { File(USER_HOME, ".korge").also { it.mkdirs() } }

object KorgeKotlinCompilerCLI {
    @JvmStatic
    fun main(args: Array<String>) {
        //RecursiveDirectoryWatcher.watch(File("../korge-kotlin-compiler/src")) { println(it) } .await()
        //RecursiveDirectoryWatcher.watch(File("..")) { println(it) }.await()
        val currentDir = File(".").canonicalFile

        try {
            if (System.getenv("KORGE_DAEMON") == "false") {
                KorgeKotlinCompilerCLISimple(currentDir, StdPipes).main(args)
            } else {
                val socketPath = File(KORGE_DIR, "/socket/compiler-${BuildConfig.KORGE_COMPILER_VERSION}.socket")
                socketPath.parentFile.mkdirs()

                //if (restartDaemon || args.firstOrNull() == "stop") {
                //    socketPath.delete()
                //}

                lateinit var client: SocketChannel
                var startedDaemon = false

                //Runtime.getRuntime().exec("taskkill /F /IM java.exe /T").waitFor()

                if (verbose) println("[CLIENT] Connecting to socket...")
                for (n in 0 until 20) {
                    try {
                        client = SocketChannel.open(UnixDomainSocketAddress.of(socketPath.toPath()))
                        break
                    } catch (e: Throwable) {
                        if (e is ConnectException || e is SocketException) {
                            if (!startedDaemon) {
                                startedDaemon = true
                                if (verbose) println("[CLIENT] Starting daemon...")
                                //JvmMeta.runOtherEntryPointSameClassPath(KorgeKotlinCompilerCLIDaemon::class, socketPath.absolutePath, waitEnd = false, shutdownHook = true)
                                JvmMeta.runOtherEntryPointSameClassPath(
                                    KorgeKotlinCompilerCLIDaemon::class,
                                    socketPath.absolutePath,
                                    waitEnd = false,
                                    shutdownHook = false,
                                    inheritIO = false
                                )
                            }
                            //JvmMeta.runOtherEntryPointSameClassPath(KorgeKotlinCompilerCLIDaemon::class, socketPath.absolutePath, waitEnd = false, shutdownHook = false)
                            Thread.sleep(50L)
                        } else {
                            throw e
                        }
                    }
                }

                if (verbose) println("[CLIENT] Sending command...")
                client.writePacket(Packet(Packet.TYPE_COMMAND) {
                    writeStringLen(currentDir.absolutePath)
                    writeStringLenListLen(args.toList())
                })

                while (client.isOpen) {
                    val packet = client.readPacket()
                    if (verbose) println("[CLIENT] Received packet $packet")
                    when (packet.type) {
                        Packet.TYPE_STDOUT -> {
                            System.out.write(packet.data)
                            System.out.flush()
                        }

                        Packet.TYPE_STDERR -> {
                            System.err.print("\u001b[91m")
                            System.err.write(packet.data)
                            System.err.flush()
                            System.err.print("\u001b[m")
                        }

                        Packet.TYPE_END -> client.close()
                    }
                }
            }
        } finally {
            virtualVirtualExecutor.awaitTermination(10L, TimeUnit.MILLISECONDS)
            threadExecutor.awaitTermination(10L, TimeUnit.MILLISECONDS)
        }
    }
}

object KorgeKotlinCompilerCLIDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        println("[DAEMON] main")

        val socketPath = args.firstOrNull() ?: error("Must provide unix socket path")

        val timeoutSeconds = 2 * 60
        var lastUpdate = System.currentTimeMillis()

        //val threads = Executors.newCachedThreadPool()

        threadExecutor.submit {
            while (true) {
                Thread.sleep(1_000L)
                if (!File(socketPath).exists()) {
                    exitProcess(-1) // Socket closed
                }
                val elapsed = System.currentTimeMillis() - lastUpdate
                if (elapsed >= timeoutSeconds * 1000L) {
                    System.err.println("NO MORE ACTIONS IN $timeoutSeconds seconds")
                    exitProcess(0)
                }
            }
        }

        try {
            kotlin.runCatching { File(socketPath).delete() }
            val address = UnixDomainSocketAddress.of(Path.of(socketPath))
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(address)
            println("[DAEMON]: Listening to $address")
            while (true) {
                try {
                    val socket = server.accept()
                    println("[DAEMON]: Accepted connection")
                    lastUpdate = System.currentTimeMillis()
                    threadExecutor.submit {
                        try {
                            while (socket.isOpen) {
                                lastUpdate = System.currentTimeMillis()
                                val packet = socket.readPacket()
                                println("[DAEMON]: Received packet $packet")
                                when (packet.type) {
                                    Packet.TYPE_COMMAND -> {
                                        val (currentDir, args) = packet.data.processBytes {
                                            val currentDir = readStringLen()
                                            val args = readStringLenListLen()
                                            Pair(currentDir, args)
                                        }
                                        try {
                                            PacketOutputStream(socket, Packet.TYPE_STDOUT).use { stdoutStream ->
                                                PacketOutputStream(socket, Packet.TYPE_STDERR).use { stderrStream ->
                                                    val pipes = StdPipes(stdoutStream, stderrStream)
                                                    val stdout = pipes.out
                                                    val stderr = pipes.err

                                                    //System.setOut(stdout)
                                                    //System.setErr(stderr)

                                                    //stdout.println("CALLING CLI")
                                                    try {
                                                        KorgeKotlinCompilerCLISimple(File(currentDir), pipes).main(args.toTypedArray())
                                                        //stdout.println("AFTER CALLING CLI")
                                                    } catch (e: ExitProcessException) {
                                                        socket.writePacket(Packet(Packet.TYPE_END))
                                                        Thread.sleep(50L)
                                                        socket.close()
                                                        exitProcess(e.exitCode)
                                                    } catch (e: Throwable) {
                                                        //stdout.println("EXCEPTION CLI")
                                                        e.printStackTrace(stderr)
                                                    }
                                                }
                                            }
                                        } finally {
                                            socket.writePacket(Packet(Packet.TYPE_END))
                                            Thread.sleep(50L)
                                            socket.close()
                                        }
                                    }
                                }
                            }
                        } finally {
                            System.gc()
                        }
                    }
                } catch (e: ConnectException) {
                    System.err.println("Socket likely closed")
                    exitProcess(-1)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    Thread.sleep(100L)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            exitProcess(-1)
        }
    }
}

class ExitProcessException(val exitCode: Int) : Exception()

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

    @JvmName("main2")
    fun main(args: Array<String>) {
        println("KorgeKotlinCompilerCLISimple.main: ${args.toList()}, stdout=$out, stderr=$err")

        val processor = CLIProcessor("KorGE Kotlin Compiler & Tools", BuildConfig.KORGE_COMPILER_VERSION, pipes)
            .registerCommand("forge", desc = "Opens the KorGE Forge installer") { forgeInstaller() }
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
            //.registerCommand("idea", desc = "Creates IDEA modules") { }
            .registerCommand("build", desc = "Builds the specified <folder> containing a KorGE project") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                KorgeKotlinCompiler(pipes).compileAllModules(
                    ProjectParser(file(path), pipes).rootModule.module,
                )
            }
            .registerCommand("test", desc = "Test the specified <folder> containing a KorGE project") {
                val path = it.removeFirstOrNull() ?: "."
                TODO()
            }
            .registerCommand("clean", desc = "Removes all the build caches") {
                val path = it.removeFirstOrNull() ?: "."
                file(path, ".korge").deleteRecursively()
                //KorgeKotlinCompiler.compileModule()
            }
            .registerCommand("new", desc = "Creates a new KorGE project in the specified <folder>") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
            }
            .registerCommand("warm", desc = "Performs a warming-up of the daemon") {
                val tempFile = File.createTempFile("temp", "kotlin")
                tempFile.delete()
                tempFile.mkdirs()

                file(tempFile, "module.yaml").also { it.parentFile.mkdirs() }.writeText("dependencies:")
                file(tempFile, "src/main.kt").also { it.parentFile.mkdirs() }.writeText(
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

                KorgeKotlinCompiler(pipes).compileAllModules(
                    ProjectParser(tempFile, pipes).rootModule.module,
                )
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
                KorgeKotlinCompiler(pipes).compileAndRun(
                    ProjectParser(file(path), pipes).rootModule.module,
                )
            }
            /*
            .registerCommand("run:reload", desc = "Builds and runs the specified <folder> with hot reloading support") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                KorgeKotlinCompiler(pipes, reload = true).compileAndRun(
                    ProjectParser(file(path), pipes).rootModule.module,
                )
            }
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

    private fun openInIde(projectPath: File) {
        when (OS.CURRENT) {
            OS.MACOS -> {
                val files = File(USER_HOME, "Applications").listFiles()?.toList() ?: emptyList()
                val macosAppPath = files.firstOrNull { it.name.contains("KorGE Forge") }
                if (macosAppPath == null) {
                    err.println("KorGE Forge not installed, opening installer...")
                    forgeInstaller()
                    return
                }
                out.println("Opening $projectPath with $macosAppPath")
                val exe = File(macosAppPath.absoluteFile, "Contents/MacOS/korge")
                ProcessBuilder(exe.absolutePath, projectPath.absolutePath)
                    .start()
            }
            else -> {
                TODO("Not implemented in ${OS.CURRENT}")
            }
        }
    }

    private fun forgeInstaller() {
        when (OS.CURRENT) {
            OS.WINDOWS -> {
                val dir = file(USER_HOME, "Downloads").takeIf { it.isDirectory } ?: KORGE_DIR
                val data = URL("https://forge.korge.org/install-korge-forge.cmd").readBytes()
                file(dir, "install-korge-forge.cmd").writeBytes(data)
                ProcessBuilder()
                    .command("cmd", "/c", "install-korge-forge.cmd")
                    .directory(dir)
                    .start()
                    .redirectToWaitFor(pipes)
            }

            else -> {
                val dir = File(USER_HOME, "Downloads").takeIf { it.isDirectory } ?: KORGE_DIR
                val data = URL("https://forge.korge.org/install-korge-forge.sh").readBytes()
                File(dir, "install-korge-forge.sh").writeBytes(data)
                ProcessBuilder()
                    .command("sh", "install-korge-forge.sh")
                    .directory(dir)
                    .start()
                    .redirectToWaitFor(pipes)
            }
        }
    }
}
