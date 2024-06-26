package korlibs.korge.kotlincompiler.util

import java.io.*

fun File.takeIfExists(): File? = this.takeIf { it.exists() }
fun File.takeIfIsDirectory(): File? = this.takeIf { it.isDirectory }
