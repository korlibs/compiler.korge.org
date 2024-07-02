package korlibs.korge.kotlincompiler

import korlibs.korge.kotlincompiler.socket.*
import korlibs.korge.kotlincompiler.util.*
import java.io.*
import java.net.*
import java.nio.channels.*
import java.nio.file.*
import kotlin.system.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

object KorgeKotlinCompilerCLIDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        println("[DAEMON] main")

        val socketPath = args.firstOrNull() ?: error("Must provide unix socket path")

        val daemonTimeout = 30.minutes
        var lastUpdate = System.currentTimeMillis()

        //val threads = Executors.newCachedThreadPool()

        threadExecutor.submit {
            while (true) {
                Thread.sleep(1_000L)
                if (!File(socketPath).exists()) {
                    exitProcess(-1) // Socket closed
                }
                val elapsed = (System.currentTimeMillis() - lastUpdate).milliseconds
                if (elapsed >= daemonTimeout) {
                    System.err.println("NO MORE ACTIONS IN $daemonTimeout seconds")
                    exitProcess(0)
                }
            }
        }

        try {
            kotlin.runCatching {
                File(socketPath).also {
                    it.delete()
                    it.deleteOnExit()
                }
            }
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
                                        val (currentDir, args, envs) = packet.data.processBytes {
                                            val currentDir = readStringLen()
                                            val args = readStringLenListLen()
                                            val envs = readStringLenListLen()
                                            Triple(currentDir, args, envs)
                                        }

                                        val envsMap = envs.associate {
                                            val parts = it.split("=", limit = 2)
                                            parts[0] to parts.getOrElse(1) { "" }
                                        }

                                        try {
                                            PacketOutputStream(socket, Packet.TYPE_STDOUT).use { stdoutStream ->
                                                PacketOutputStream(socket, Packet.TYPE_STDERR).use { stderrStream ->
                                                    val pipes = StdPipes(stdoutStream, stderrStream)
                                                    val stdout = pipes.out
                                                    val stderr = pipes.err

                                                    //stdout.println("envsMap: $envsMap")

                                                    //System.setOut(stdout)
                                                    //System.setErr(stderr)

                                                    //stdout.println("CALLING CLI")
                                                    try {
                                                        KorgeKotlinCompilerCLISimple(File(currentDir), pipes).main(args.toTypedArray(), envsMap)
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