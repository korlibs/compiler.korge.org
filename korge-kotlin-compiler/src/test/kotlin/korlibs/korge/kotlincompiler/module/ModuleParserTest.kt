package korlibs.korge.kotlincompiler.module

import korlibs.korge.kotlincompiler.*
import korlibs.korge.kotlincompiler.util.*
import java.io.*
import kotlin.test.*

class ModuleParserTest {
    @Test
    fun test() {

        //KorgeKotlinCompiler.compileAndRun(
        //    ProjectParser(File("C:\\Users\\soywiz\\projects\\korge-snake")).rootModule.module
        //)


        val parser = ProjectParser(File("../examples/project1"), StdPipes)
        println(parser.rootModule.module.libsFiles)
        for (module in parser.allModules) {
            println(module)
        }
        //GitHubRepoRefPath("korlibs", "korge-ext", "v0.1.3", "korge-frameblock").extractTo(File("/tmp/korge-frameblock"))
    }
}