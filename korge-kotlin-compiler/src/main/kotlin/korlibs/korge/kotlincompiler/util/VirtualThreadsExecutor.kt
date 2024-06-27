package korlibs.korge.kotlincompiler.util

import java.util.concurrent.*

val virtualVirtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
val threadExecutor = Executors.newCachedThreadPool {
    Executors.defaultThreadFactory().newThread(it).also { it.isDaemon = true }
}
