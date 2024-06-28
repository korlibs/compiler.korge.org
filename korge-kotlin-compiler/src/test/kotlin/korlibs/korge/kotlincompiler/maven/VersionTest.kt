package korlibs.korge.kotlincompiler.maven

import java.lang.Runtime.*
import kotlin.test.*

class VersionTest {
    @Test
    fun test() {
        assertEquals(true, MavenVersion("2.0.0-alpha2") > MavenVersion("1.0.0"))
        assertEquals(true, MavenVersion("2.0.0") compareTo MavenVersion("2.0.0.0") == 0)
        assertEquals(true, MavenVersion("2.0.1") > MavenVersion("2.0.0"))
        assertEquals(true, MavenVersion("20") > MavenVersion("3"))
        assertEquals(true, MavenVersion("20.1") > MavenVersion("20.0"))
        assertEquals(true, MavenVersion("20.0") < MavenVersion("20.1"))
    }
}