package korlibs.korge.kotlincompiler

import korlibs.korge.kotlincompiler.socket.*
import korlibs.korge.kotlincompiler.util.*
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.channels.*
import java.nio.file.*
import kotlin.coroutines.*
import kotlin.system.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

object KorgeKotlinCompilerCLIDaemon {
    var lastUpdate = System.currentTimeMillis()

    @JvmStatic
    fun main(args: Array<String>) {
        println("[DAEMON] main")

        val socketPath = args.firstOrNull() ?: error("Must provide unix socket path")

        val daemonTimeout = 30.minutes

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
                            runBlocking {
                                processClient(socket)
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

    private suspend fun processClient(socket: SocketChannel) {
        while (socket.isOpen) {
            lastUpdate = System.currentTimeMillis()
            val packet = socket.readPacket()
            println("[DAEMON]: Received packet $packet")
            //JOptionPane.showConfirmDialog(null, "[DAEMON]: Received packet $packet")
            when (packet.type) {
                Packet.TYPE_END -> {
                    //JOptionPane.showConfirmDialog(null, "[DAEMON]: END")
                    socket.close()
                }
                Packet.TYPE_COMMAND -> {
                    lateinit var currentDir: String
                    lateinit var args: List<String>
                    lateinit var envs: List<String>
                    var pid: Long = 0L

                    packet.data.processBytes {
                        currentDir = readStringLen()
                        args = readStringLenListLen()
                        envs = readStringLenListLen()
                        pid = readLong()
                    }

                    val envsMap = envs.associate {
                        val parts = it.split("=", limit = 2)
                        parts[0] to parts.getOrElse(1) { "" }
                    }

                    val job = CoroutineScope(threadExecutorDispatcher).launch {
                        try {
                            PacketOutputStream(socket, Packet.TYPE_STDOUT).use { stdoutStream ->
                                PacketOutputStream(socket, Packet.TYPE_STDERR).use { stderrStream ->
                                    val pipes = StdPipes(stdoutStream, stderrStream)

                                    //stdout.println("envsMap: $envsMap")
                                    //System.setOut(stdout)
                                    //System.setErr(stderr)

                                    //stdout.println("CALLING CLI")
                                    try {
                                        KorgeKotlinCompilerCLISimple(File(currentDir), pipes).suspendMain(
                                            args.toTypedArray(),
                                            envsMap,
                                            pid = pid
                                        ) { socket.isOpen }
                                        //stdout.println("AFTER CALLING CLI")
                                    } catch (e: ExitProcessException) {
                                        socket.writePacket(Packet(Packet.TYPE_END))
                                        Thread.sleep(50L)
                                        socket.close()
                                        exitProcess(e.exitCode)
                                    } catch (e: Throwable) {
                                        //stdout.println("EXCEPTION CLI")
                                        e.printStackTrace(pipes.err)
                                    }
                                }
                            }
                        } finally {
                            socket.writePacket(Packet(Packet.TYPE_END))
                            delay(50L)
                            socket.close()
                        }
                    }
                    CoroutineScope(coroutineContext).launch {
                        try {
                            while (socket.isOpen) {
                                delay(50L)
                            }
                        } finally {
                            job.cancelAndJoin()
                        }
                    }
                }
            }
        }
    }
}