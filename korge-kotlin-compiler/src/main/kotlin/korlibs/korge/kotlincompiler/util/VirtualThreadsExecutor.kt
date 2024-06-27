package korlibs.korge.kotlincompiler.util

import java.util.concurrent.*

//val virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
val virtualExecutor = Executors.newCachedThreadPool()
