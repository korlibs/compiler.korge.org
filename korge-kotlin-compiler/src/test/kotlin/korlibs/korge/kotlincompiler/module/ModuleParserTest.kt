package korlibs.korge.kotlincompiler.module

import korlibs.korge.kotlincompiler.git.*
import java.io.*
import kotlin.test.*

class ModuleParserTest {
    @Test
    fun test() {
        val parser = ModuleParser(File("../examples/project1"))
        parser.parse()
        println(parser.projectDir.absoluteFile)
        println(parser.moduleName)
        println(parser.mavenDeps)
        //GitHubRepoRefPath("korlibs", "korge-ext", "v0.1.3", "korge-frameblock").extractTo(File("/tmp/korge-frameblock"))
    }
}