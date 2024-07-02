package korlibs.korge.kotlincompiler.util

import kotlinx.coroutines.*
import java.util.concurrent.*

val virtualVirtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
val threadExecutor = Executors.newCachedThreadPool {
    Executors.defaultThreadFactory().newThread(it).also { it.isDaemon = true }
}

val virtualVirtualExecutorDispatcher = virtualVirtualExecutor.asCoroutineDispatcher()
val threadExecutorDispatcher = threadExecutor.asCoroutineDispatcher()
