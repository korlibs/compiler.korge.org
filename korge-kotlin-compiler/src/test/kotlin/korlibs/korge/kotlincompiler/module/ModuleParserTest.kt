package korlibs.korge.kotlincompiler.module

import java.io.*
import kotlin.test.*

class ModuleParserTest {
    @Test
    fun test() {
        val parser = ProjectParser(File("../examples/project1"))
        println(parser.rootModule.module.libsFiles)
        for (module in parser.allModules) {
            println(module)
        }
        //GitHubRepoRefPath("korlibs", "korge-ext", "v0.1.3", "korge-frameblock").extractTo(File("/tmp/korge-frameblock"))
    }
}