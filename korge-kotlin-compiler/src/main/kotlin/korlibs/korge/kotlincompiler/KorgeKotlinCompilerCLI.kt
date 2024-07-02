@file:OptIn(ExperimentalStdlibApi::class)

package korlibs.korge.kotlincompiler

import korlibs.korge.kotlincompiler.socket.*
import korlibs.korge.kotlincompiler.util.*
import java.io.*
import java.net.*
import java.nio.channels.*
import java.util.concurrent.*

// taskkill /F /IM java.exe /T

val verbose by lazy { System.getenv("KORGE_VERBOSE") == "true" }
//val verbose = true
//val restartDaemon = false
//val restartDaemon = System.getenv("KORGE_DAEMON_RESTART_ALWAYS") == "true"

val USER_HOME by lazy { File(System.getProperty("user.home")) }
val KORGE_DIR by lazy { File(USER_HOME, ".korge").also { it.mkdirs() } }
val TMP_DIR by lazy { File(System.getProperty("java.io.tmpdir")) }

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
                val socketPath = File(TMP_DIR, "/korge.socket.compiler.${BuildConfig.KORGE_COMPILER_VERSION}.socket")
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
                    writeStringLenListLen(System.getenv().map { "${it.key}=${it.value}" })
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

