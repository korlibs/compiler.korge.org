package korlibs.korge.kotlincompiler.maven

import korlibs.korge.kotlincompiler.util.*
import java.io.*
import java.net.*

fun Collection<MavenArtifact>.getMavenArtifacts(pipes: StdPipes): Set<File> = this.flatMap { it.getMavenArtifacts(pipes) }.toSet()

fun Collection<MavenArtifact>.mergeMavenArtifacts(): Set<MavenArtifact> {
    val versions = this.groupBy { it.copy(version = "") }
    return versions.map { it.value.maxByOrNull { it.mavenVersion }!! }.toSet()
}

fun MavenArtifact.resolveAllMavenArtifacts(pipes: StdPipes, explored: MutableSet<MavenArtifact> = mutableSetOf()): Set<MavenArtifact> {
    val explore = ArrayDeque<MavenArtifact>()
    explore += this
    val out = mutableSetOf<MavenArtifact>()
    while (explore.isNotEmpty()) {
        val artifact = explore.removeFirst()
        if (artifact in explored) continue
        explored += artifact
        val pom = Pom.parse(artifact.copy(extension = "pom").getSingleMavenArtifact(pipes))
        if (pom.packaging == null || pom.packaging == "jar") {
            out += artifact
        }
        for (dep in pom.deps) {
            explore += dep.artifact
        }
    }
    return out.mergeMavenArtifacts()
}

fun MavenArtifact.getMavenArtifacts(pipes: StdPipes): Set<File> {
    return resolveAllMavenArtifacts(pipes).map { it.getSingleMavenArtifact(pipes) }.toSet()
}

fun MavenArtifact.getSingleMavenArtifact(pipes: StdPipes): File {
    val file = File(System.getProperty("user.home"), ".m2/repository/${localPath}")
    if (!file.exists()) {
        file.parentFile.mkdirs()
        val url = URL("https://repo1.maven.org/maven2/${localPath}")
        pipes.out.println("Downloading $url")
        file.writeBytes(url.readBytes())
    }
    return file
}
