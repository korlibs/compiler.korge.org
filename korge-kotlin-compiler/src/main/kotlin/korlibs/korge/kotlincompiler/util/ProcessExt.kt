package korlibs.korge.kotlincompiler.util

fun ProcessBuilder.startEnsuringDestroyed(shutdownHook: Boolean = true): Process {
    val process = start()
    if (shutdownHook) Runtime.getRuntime().addShutdownHook(Thread { process.destroy(); process.destroyForcibly() })
    return process
}