package korlibs.korge.kotlincompiler.util

import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ThreadPoolExecutor

fun ProcessBuilder.startEnsuringDestroyed(shutdownHook: Boolean = true): Process {
    val process = start()
    if (shutdownHook) Runtime.getRuntime().addShutdownHook(Thread { process.destroy(); process.destroyForcibly() })
    return process
}

fun Process.redirectTo(out: OutputStream, err: OutputStream) {
    mainExecutor.execute {
        while (true) {
            val available = inputStream.available()
            out.write(inputStream.readNBytes(maxOf(available, 1)))
        }
    }
    mainExecutor.execute {
        while (true) {
            val available = errorStream.available()
            err.write(errorStream.readNBytes(maxOf(available, 1)))
        }
    }
}