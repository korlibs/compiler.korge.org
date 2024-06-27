package korlibs.korge.kotlincompiler

import korlibs.korge.kotlincompiler.module.*
import korlibs.korge.kotlincompiler.socket.*
import korlibs.korge.kotlincompiler.util.*
import org.jetbrains.kotlin.buildtools.api.*
import java.io.*
import java.net.*
import java.nio.channels.*
import java.nio.file.*
import kotlin.system.*

// taskkill /F /IM java.exe /T

val verbose = System.getenv("KORGE_VERBOSE") == "true"
//val verbose = true
//val restartDaemon = false
//val restartDaemon = System.getenv("KORGE_DAEMON_RESTART_ALWAYS") == "true"

val USER_HOME by lazy { File(System.getProperty("user.home")) }
val KORGE_DIR by lazy { File(USER_HOME, ".korge").also { it.mkdirs() } }

object KorgeKotlinCompilerCLI {
    @JvmStatic
    fun main(args: Array<String>) {
        if (System.getenv("KORGE_DAEMON") == "false") {
            //if (true) {
            KorgeKotlinCompilerCLISimple.main(args)
            return
        } else {
            val socketPath = File(KORGE_DIR, "/socket/compiler.socket")
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
                writeStringLenListLen(args.toList())
            })

            while (client.isOpen) {
                val packet = client.readPacket()
                if (verbose) println("[CLIENT] Received packet $packet")
                when (packet.type) {
                    Packet.TYPE_STDOUT -> System.out.print(packet.data.decodeToString())
                    Packet.TYPE_STDERR -> System.err.print(packet.data.decodeToString())
                    Packet.TYPE_END -> client.close()
                }
            }
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

        mainExecutor.submit {
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
                    mainExecutor.submit {
                        while (socket.isOpen) {
                            lastUpdate = System.currentTimeMillis()
                            val packet = socket.readPacket()
                            println("[DAEMON]: Received packet $packet")
                            when (packet.type) {
                                Packet.TYPE_COMMAND -> {
                                    val args = packet.data.processBytes { readStringLenListLen() }
                                    try {
                                        PacketOutputStream(socket, Packet.TYPE_STDOUT).use { stdoutStream ->
                                            PacketOutputStream(socket, Packet.TYPE_STDERR).use { stderrStream ->
                                                val stdout = PrintStream(stdoutStream)
                                                val stderr = PrintStream(stderrStream)

                                                //stdout.println("CALLING CLI")
                                                try {
                                                    KorgeKotlinCompilerCLISimple(stdout, stderr).main(args.toTypedArray())
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

class KorgeKotlinCompilerCLISimple(val stdout: PrintStream = System.out, val stderr: PrintStream = System.err) {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            KorgeKotlinCompilerCLISimple(System.out, System.err).main(args)
        }
    }

    @JvmName("main2")
    fun main(args: Array<String>) {
        println("KorgeKotlinCompilerCLISimple.main: ${args.toList()}, stdout=$stdout, stderr=$stderr")

        val processor = CLIProcessor("KorGE Kotlin Compiler & Tools", "0.0.1-alpha", stdout, stderr)
            .registerCommand("forge", desc = "Opens the KorGE Forge installer") { ide() }
            .registerCommand("version", desc = "Displays compiler version") { stdout.println(KorgeKotlinCompiler.getCompilerVersion()) }
            //.registerCommand("idea", desc = "Creates IDEA modules") { }
            .registerCommand("build", desc = "Builds the specified <folder> containing a KorGE project") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                KorgeKotlinCompiler(stdout, stderr).compileAllModules(
                    ProjectParser(File(path)).rootModule.module,
                )
            }
            .registerCommand("clean", desc = "Removes all the build caches") {
                val path = it.removeFirstOrNull() ?: "."
                File(path, ".korge").deleteRecursively()
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

                File(tempFile, "module.yaml").also { it.parentFile.mkdirs() }.writeText("dependencies:")
                File(tempFile, "src/main.kt").also { it.parentFile.mkdirs() }.writeText("""
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
                """.trimIndent())

                KorgeKotlinCompiler(stdout, stderr).compileAllModules(
                    ProjectParser(tempFile).rootModule.module,
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
                KorgeKotlinCompiler(stdout, stderr).compileAndRun(
                    ProjectParser(File(path)).rootModule.module,
                )
            }
            .registerCommand("run:reload", desc = "Builds and runs the specified <folder> with hot reloading support") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
                KorgeKotlinCompiler(stdout, stderr).compileAndRun(
                    ProjectParser(File(path)).rootModule.module,
                )
            }
            .registerCommand("package:jvm", desc = "Packages a far jar file for the specified <folder> containing a KorGE project") {
                val path = it.removeFirstOrNull() ?: "."
                //KorgeKotlinCompiler.compileModule()
                TODO()
            }
            .registerCommand("wrapper", desc = "Update wrapper to version <version>") {
                val version = it.removeFirstOrNull() ?: error("version not specified")
                //KorgeKotlinCompiler.compileModule()
                stdout.println("[FAKE]: Updating to... $version")
                TODO()
            }
            .registerCommand("stop", desc = "Stops the daemon") {
                throw ExitProcessException(0)
            }

        processor.process(args)
    }

    fun ide() {
        when (OS.CURRENT) {
            OS.WINDOWS -> {
                val dir = File(USER_HOME, "Downloads").takeIf { it.isDirectory } ?: KORGE_DIR
                val data = URL("https://forge.korge.org/install-korge-forge.cmd").readBytes()
                File(dir, "install-korge-forge.cmd").writeBytes(data)
                ProcessBuilder()
                    .command("cmd", "/c", "install-korge-forge.cmd")
                    .directory(dir)
                    .start()
                    .redirectTo(stdout, stderr)
                    .waitFor()
            }

            else -> {
                val dir = File(USER_HOME, "Downloads").takeIf { it.isDirectory } ?: KORGE_DIR
                val data = URL("https://forge.korge.org/install-korge-forge.sh").readBytes()
                File(dir, "install-korge-forge.sh").writeBytes(data)
                ProcessBuilder()
                    .command("sh", "install-korge-forge.sh")
                    .directory(dir)
                    .start()
                    .redirectTo(stdout, stderr)
                    .waitFor()
            }
        }
    }
}
