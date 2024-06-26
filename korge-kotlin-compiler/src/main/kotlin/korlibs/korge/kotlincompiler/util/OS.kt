package korlibs.korge.kotlincompiler.util

enum class OS {
    WINDOWS, LINUX, MACOS;

    companion object {
        val CURRENT by lazy { detectOS() }

        private fun detectOS(): OS {
            val osName = System.getProperty("os.name").lowercase()
            return when {
                osName.contains("win") -> OS.WINDOWS
                osName.contains("mac") -> OS.MACOS
                osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> OS.LINUX
                else -> error("Unsupported OS: $osName")
            }
        }
    }
}

