package korlibs.korge.kotlincompiler

import korlibs.korge.kotlincompiler.socket.*
import korlibs.korge.kotlincompiler.util.*
import java.io.*
import java.net.*
import java.nio.channels.*
import java.nio.file.*
import kotlin.system.*

val verbose = System.getenv("KORGE_VERBOSE") == "true"
//val verbose = true
//val restartDaemon = false
val restartDaemon = System.getenv("KORGE_DAEMON_RESTART_ALWAYS") == "true"

object KorgeKotlinCompilerCLI {
    @JvmStatic
    fun main(args: Array<String>) {
        if (System.getenv("KORGE_DAEMON") == "false") {
        //if (true) {
            KorgeKotlinCompilerCLISimple.main(args)
            return
        } else {
            val socketPath = File("temp2.socket")
            lateinit var client: SocketChannel
            var startedDaemon = false

            if (restartDaemon) {
                socketPath.delete()
            }

            //Runtime.getRuntime().exec("taskkill /F /IM java.exe /T").waitFor()

            if (verbose) println("[CLIENT] Connecting to socket...")
            for (n in 0 until 20) {
                try {
                    client = SocketChannel.open(UnixDomainSocketAddress.of(socketPath.toPath()))
                    break
                } catch (e: ConnectException) {
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
                Thread.sleep(10_000L)
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
                                                    KorgeKotlinCompilerCLISimple.main(args.toTypedArray(), stdout, stderr)
                                                    //stdout.println("AFTER CALLING CLI")
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

object KorgeKotlinCompilerCLISimple {
    @JvmStatic
    fun main(args: Array<String>) {
        main(args, System.out, System.err)
    }

    @JvmStatic
    fun main(args: Array<String>, stdout: PrintStream, stderr: PrintStream) {
        println("KorgeKotlinCompilerCLISimple.main: ${args.toList()}, stdout=$stdout, stderr=$stderr")

        val processor = CLIProcessor("KorGE Compiler", "0.0.1-alpha", stdout, stderr)
            .registerCommand("ide", desc = "Opens the IDE installer") {
                when (OS.CURRENT) {
                    OS.WINDOWS -> {
                        val userHome = File(System.getProperty("user.home"))
                        val korgeDir = File(userHome, ".korge").also { it.mkdirs() }
                        val downloadsDir = File(userHome, "Downloads")
                        val dir = downloadsDir.takeIf { it.isDirectory } ?: korgeDir
                        val data = URL("https://forge.korge.org/install-korge-forge.cmd").readBytes()
                        File(dir, "install-korge-forge.cmd").writeBytes(data)
                        val process = ProcessBuilder()
                            .command("cmd", "/c", "install-korge-forge.cmd")
                            .directory(dir)
                            .start()
                        process.redirectTo(stdout, stderr)
                        process.waitFor()
                    }
                    OS.LINUX -> TODO()
                    OS.MACOS -> TODO()
                }
            }
            .registerCommand("exit", desc = "Stops the daemon") {
                exitProcess(0)
            }

        processor.process(args)

        //val runtimeMxBean = ManagementFactory.getRuntimeMXBean()
        //val arguments = runtimeMxBean.inputArguments
        //stdout.println(args.toList())
        //stdout.println(ProcessHandle.current()
        //    .info()
        //    .command()
        //    .orElseThrow())
        //stdout.println(System.getProperty("java.class.path"))

        /*
        return

        for (line in System.`in`.bufferedReader().lineSequence()) {
            val command = line.substringBefore(' ')
            val params = line.substringAfter(' ', "")
            when (command) {
                "listen" -> {
                    val socketFile = File("$params.socket")
                    val pidFile = File("$params.pid")
                    val currentPid = ProcessHandle.current().pid()
                    pidFile.writeText("$currentPid")
                    //println("Listening on $currentPid")
                    TODO()
                }
                "ide" -> {
                    System.exit(0)
                }
                "exit" -> {
                    System.exit(0)
                }
                "compile" -> {
                }
                "run" -> {
                }
                "stop" -> {
                }
                "package:jvm" -> {
                }
                //"package:js" -> {
                //}
                //"package:wasm" -> {
                //}
                //"package:ios" -> {
                //}
                //"package:android" -> {
                //}
            }
        }
        */
    }
}
