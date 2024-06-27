package korlibs.korge.kotlincompiler.util

import java.io.*
import java.util.concurrent.*

fun ProcessBuilder.startEnsuringDestroyed(shutdownHook: Boolean = true): Process {
    val process = start()
    if (shutdownHook) {
        val shutdownHook = Thread { process.destroy(); process.destroyForcibly() }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        threadExecutor.submit {
            process.waitFor()
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        }
    }
    return process
}

open class StdPipes(val out: PrintStream = System.out, val err: PrintStream = System.err) {
    constructor(out: OutputStream, err: OutputStream) : this(PrintStream(out), PrintStream(err))

    companion object : StdPipes()
}

fun Process.redirectTo(pipes: StdPipes, wait: Boolean = false): Process = redirectTo(pipes.out, pipes.err, wait)
fun Process.redirectToWaitFor(pipes: StdPipes): Int {
    val process = redirectTo(pipes, wait = true)
    return process.waitFor()
}

private fun Process.redirectTo(out: OutputStream, err: OutputStream, wait: Boolean = false): Process {
    fun doExecute(inp: InputStream, out: OutputStream): Future<*> = virtualVirtualExecutor.submit {
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

    val outProcess = doExecute(inputStream, out)
    val errProcess = doExecute(errorStream, err)

    if (wait) {
        outProcess.get()
        errProcess.get()
    }
    return this
}