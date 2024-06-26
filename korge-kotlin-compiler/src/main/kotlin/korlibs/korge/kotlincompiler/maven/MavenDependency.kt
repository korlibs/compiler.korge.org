package korlibs.korge.kotlincompiler.maven

data class MavenDependency(
    val artifact: MavenArtifact,
    val scope: String = "compile",
    val sourceSet: String = "common",
)
