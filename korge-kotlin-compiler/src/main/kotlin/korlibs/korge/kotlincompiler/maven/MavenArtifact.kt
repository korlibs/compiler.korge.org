package korlibs.korge.kotlincompiler.maven

data class MavenArtifact(
    val group: String,
    val name: String,
    val version: String,
    val classifier: String? = null,
    val extension: String = "jar"
) {
    companion object {
        operator fun invoke(coordinates: String): MavenArtifact {
            val parts = coordinates.split(":")
            return MavenArtifact(parts[0], parts[1], parts.getOrElse(2) { "" })
        }
    }

    val groupSeparator by lazy { group.replace(".", "/") }
    val localPath by lazy {
        if (version.isEmpty()) error("Can't get local path for $this because missing version")
        "$groupSeparator/$name/$version/$name-$version.$extension"
    }
}
