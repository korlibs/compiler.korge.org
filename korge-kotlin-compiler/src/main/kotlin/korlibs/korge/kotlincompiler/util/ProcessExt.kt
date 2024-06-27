package korlibs.korge.kotlincompiler.util

import java.io.*

fun ProcessBuilder.startEnsuringDestroyed(shutdownHook: Boolean = true): Process {
    val process = start()
    if (shutdownHook) {
        val shutdownHook = Thread { process.destroy(); process.destroyForcibly() }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        virtualExecutor.submit {
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

fun Process.redirectTo(pipes: StdPipes): Process = redirectTo(pipes.out, pipes.err)

fun Process.redirectTo(out: OutputStream, err: OutputStream): Process {
    virtualExecutor.execute {
        while (true) {
            val available = inputStream.available()
            val bytes = inputStream.readNBytes(maxOf(available, 1))
            if (bytes.isNotEmpty()) {
                //if (bytes.isEmpty()) break
                out.write(bytes)
            }
            Thread.sleep(100L)
        }
    }
    virtualExecutor.execute {
        while (true) {
            val available = errorStream.available()
            val bytes = errorStream.readNBytes(maxOf(available, 1))
            if (bytes.isNotEmpty()) {
                //if (bytes.isEmpty()) break
                err.write(bytes)
            }
            Thread.sleep(100L)
        }
    }
    return this
}