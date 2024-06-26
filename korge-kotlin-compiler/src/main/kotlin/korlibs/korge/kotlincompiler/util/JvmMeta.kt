package korlibs.korge.kotlincompiler.util

import java.io.*
import kotlin.reflect.*

object JvmMeta {
    val javaExecutablePath by lazy {
        ProcessHandle.current()
            .info()
            .command()
            .orElseThrow()
    }

    fun runOtherEntryPointSameClassPath(clazz: KClass<*>, vararg args: String, envs: Map<String, String> = emptyMap(), cwd: File = File(".").absoluteFile, waitEnd: Boolean = true, shutdownHook: Boolean = true, inheritIO: Boolean = true): Process {
        return runOtherEntryPointSameClassPath(clazz.qualifiedName!!.replace("/", "."), *args, envs = envs, cwd = cwd, waitEnd = waitEnd, shutdownHook = shutdownHook, inheritIO = inheritIO)
    }

    fun runOtherEntryPointSameClassPath(newMainClass: String, vararg args: String, envs: Map<String, String> = emptyMap(), cwd: File = File(".").absoluteFile, waitEnd: Boolean = true, shutdownHook: Boolean = true, inheritIO: Boolean = true): Process {
        //val javaHome = System.getProperty("java.home")
        //val javaBin = "$javaHome/bin/java"

        val javaBin = javaExecutablePath
        val classpath = System.getProperty("java.class.path")

        val command = buildList<String> {
            add(javaBin)
            add("-cp")
            add(classpath)
            add(newMainClass)
            addAll(args)
        }

        println("Command: " + java.lang.String.join(" ", command))

        val processBuilder = ProcessBuilder(command)
        if (inheritIO) processBuilder.inheritIO()
        val process = processBuilder
            .directory(cwd)
            .also { it.environment().putAll(envs) }
            .startEnsuringDestroyed(shutdownHook = shutdownHook)
        if (waitEnd) process.waitFor()
        return process
    }
}