package korlibs.korge.kotlincompiler.maven

inline class MavenVersion(val version: String) : Comparable<MavenVersion> {
    override fun compareTo(other: MavenVersion): Int = MavenVersion.compareVersions(this.version, other.version)

    companion object {
        fun compareVersions(a: String, b: String): Int {
            val aParts = a.split(".")
            val bParts = b.split(".")
            for (n in 0 until maxOf(aParts.size, bParts.size)) {
                val aPart = aParts.getOrElse(n) { "0" }
                val bPart = bParts.getOrElse(n) { "0" }
                val aPartInt = aPart.toIntOrNull()
                val bPartInt = bPart.toIntOrNull()
                when {
                    aPartInt != null && bPartInt != null -> aPartInt.compareTo(bPartInt).let { if (it != 0) return it }
                    else -> aPart.compareTo(bPart).let { if (it != 0) return it }
                }
            }
            return 0
        }
    }
}
