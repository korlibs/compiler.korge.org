package korlibs.korge.kotlincompiler.maven

import java.io.*
import java.net.*

fun List<MavenArtifact>.getMavenArtifacts(): Set<File> = this.flatMap { it.getMavenArtifacts() }.toSet()

fun MavenArtifact.getMavenArtifacts(explored: MutableSet<MavenArtifact> = mutableSetOf()): Set<File> {
    val explore = ArrayDeque<MavenArtifact>()
    explore += this
    val out = mutableSetOf<MavenArtifact>()
    while (explore.isNotEmpty()) {
        val artifact = explore.removeFirst()
        if (artifact in explored) continue
        explored += artifact
        val pom = Pom.parse(artifact.copy(extension = "pom").getSingleMavenArtifact())
        if (pom.packaging == null || pom.packaging == "jar") {
            out += artifact
        }
        for (dep in pom.deps) {
            explore += dep.artifact
        }
    }
    return out.map { it.getSingleMavenArtifact() }.toSet()
}

fun MavenArtifact.getSingleMavenArtifact(): File {
    val file = File(System.getProperty("user.home"), ".m2/repository/${localPath}")
    if (!file.exists()) {
        file.parentFile.mkdirs()
        val url = URL("https://repo1.maven.org/maven2/${localPath}")
        println("Downloading $url")
        file.writeBytes(url.readBytes())
    }
    return file
}
