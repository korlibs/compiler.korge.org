package korlibs.korge.kotlincompiler.util

import java.io.*

fun ProcessBuilder.startEnsuringDestroyed(shutdownHook: Boolean = true): Process {
    val process = start()
    if (shutdownHook) Runtime.getRuntime().addShutdownHook(Thread { process.destroy(); process.destroyForcibly() })
    return process
}

fun Process.redirectTo(out: OutputStream, err: OutputStream): Process {
    mainExecutor.execute {
        while (true) {
            val available = inputStream.available()
            val bytes = inputStream.readNBytes(maxOf(available, 1))
            //if (bytes.isEmpty()) break
            out.write(bytes)
            Thread.sleep(1L)
        }
    }
    mainExecutor.execute {
        while (true) {
            val available = errorStream.available()
            val bytes = inputStream.readNBytes(maxOf(available, 1))
            //if (bytes.isEmpty()) break
            err.write(bytes)
            Thread.sleep(1L)
        }
    }
    return this
}