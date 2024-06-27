package korlibs.korge.kotlincompiler.util

import java.io.*

fun ProcessBuilder.startEnsuringDestroyed(shutdownHook: Boolean = true): Process {
    val process = start()
    if (shutdownHook) Runtime.getRuntime().addShutdownHook(Thread { process.destroy(); process.destroyForcibly() })
    return process
}

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