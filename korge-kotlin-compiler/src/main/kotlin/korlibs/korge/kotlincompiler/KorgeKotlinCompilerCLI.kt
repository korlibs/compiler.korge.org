package korlibs.korge.kotlincompiler

import korlibs.korge.kotlincompiler.socket.*
import korlibs.korge.kotlincompiler.util.*
import java.io.*
import java.lang.management.*
import java.net.*
import java.nio.channels.*
import java.nio.file.*
import java.util.concurrent.*
import kotlin.system.*

object KorgeKotlinCompilerCLI {
    @JvmStatic
    fun main(args: Array<String>) {
        val verbose = System.getenv("KORGE_VERBOSE") == "true"

        val socketPath = File("temp2.socket")
        lateinit var client: SocketChannel
        var startedDaemon = false

        //Runtime.getRuntime().exec("taskkill /F /IM java.exe /T").waitFor()

        if (verbose) println("[CLIENT] Connecting to socket...")
        for (n in 0 until 10) {
            try {
                client = SocketChannel.open(UnixDomainSocketAddress.of(socketPath.toPath()))
                break
            } catch (e: ConnectException) {
                if (!startedDaemon) {
                    startedDaemon = true
                    if (verbose) println("[CLIENT] Starting daemon...")
                    //JvmMeta.runOtherEntryPointSameClassPath(KorgeKotlinCompilerCLIDaemon::class, socketPath.absolutePath, waitEnd = false, shutdownHook = true)
                    JvmMeta.runOtherEntryPointSameClassPath(KorgeKotlinCompilerCLIDaemon::class, socketPath.absolutePath, waitEnd = false, shutdownHook = false, inheritIO = false)
                }
                //JvmMeta.runOtherEntryPointSameClassPath(KorgeKotlinCompilerCLIDaemon::class, socketPath.absolutePath, waitEnd = false, shutdownHook = false)
                Thread.sleep(100L)
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

object KorgeKotlinCompilerCLIDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        println("[DAEMON] main")

        val socketPath = args.firstOrNull() ?: error("Must provide unix socket path")

        val timeoutSeconds = 2 * 60
        var lastUpdate = System.currentTimeMillis()

        val threads = Executors.newVirtualThreadPerTaskExecutor()
        //val threads = Executors.newCachedThreadPool()

        threads.submit {
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
                    threads.submit {
                        while (socket.isOpen) {
                            lastUpdate = System.currentTimeMillis()
                            val packet = socket.readPacket()
                            println("[DAEMON]: Received packet $packet")
                            when (packet.type) {
                                Packet.TYPE_COMMAND -> {
                                    val args = packet.data.processBytes { readStringLenListLen() }
                                    try {
                                        PacketOutputStream(socket, Packet.TYPE_STDOUT).use { stdout ->
                                            PacketOutputStream(socket, Packet.TYPE_STDERR).use { stderr ->
                                                KorgeKotlinCompilerCLISimple.main(args.toTypedArray(), PrintStream(stdout), PrintStream(stderr))
                                            }
                                        }
                                    } finally {
                                        socket.writePacket(Packet(Packet.TYPE_END))
                                        socket.close()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
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
        //val runtimeMxBean = ManagementFactory.getRuntimeMXBean()
        //val arguments = runtimeMxBean.inputArguments
        stdout.println(args.toList())
        stdout.println(ProcessHandle.current()
            .info()
            .command()
            .orElseThrow())
        stdout.println(System.getProperty("java.class.path"))

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
    }
}
