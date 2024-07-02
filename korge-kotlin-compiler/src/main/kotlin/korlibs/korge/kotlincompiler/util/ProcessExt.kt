package korlibs.korge.kotlincompiler.util

import kotlinx.coroutines.*
import java.io.*
import kotlin.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

suspend fun ProcessBuilder.startDetached(): Process {
    return startEnsuringDestroyed(shutdownHook = false)
}

suspend fun ProcessBuilder.startEnsuringDestroyed(shutdownHook: Boolean = true): Process {
    val process = start()
    if (shutdownHook) {
        val shutdownHook = Thread { process.destroy(); process.destroyForcibly() }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        CoroutineScope(coroutineContext).launch {
            try {
                process.waitForSuspend()
            } finally {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            }
        }
    }
    return process
}

open class StdPipes(val out: PrintStream = System.out, val err: PrintStream = System.err) {
    constructor(out: OutputStream, err: OutputStream) : this(PrintStream(out), PrintStream(err))

    companion object : StdPipes()
}

suspend fun Process.redirectTo(pipes: StdPipes, wait: Boolean = false): Process = redirectTo(pipes.out, pipes.err, wait)
suspend fun Process.redirectToWaitFor(pipes: StdPipes): Int {
    val process = redirectTo(pipes, wait = true)
    return waitForSuspend()
}

suspend fun Process.waitForSuspend(): Int {
    while (isAlive) delay(50.milliseconds)
    return exitValue()
}

private suspend fun Process.redirectTo(out: OutputStream, err: OutputStream, wait: Boolean = false): Process {
    suspend fun doExecute(inp: InputStream, out: OutputStream) {
        withContext(threadExecutorDispatcher) {
            while (true) {
                val available = inp.available()
                if (available == 0 && !isAlive) break
                val bytes = inp.readNBytes(maxOf(available, 1))
                if (bytes.isNotEmpty()) {
                    //if (bytes.isEmpty()) break
                    out.write(bytes)
                }
                Thread.sleep(1L)
            }
        }
    }

    val outProcess = CoroutineScope(coroutineContext).launch { doExecute(inputStream, out) }
    val errProcess = CoroutineScope(coroutineContext).launch { doExecute(errorStream, err) }

    if (wait) {
        outProcess.join()
        errProcess.join()
    }
    return this
}