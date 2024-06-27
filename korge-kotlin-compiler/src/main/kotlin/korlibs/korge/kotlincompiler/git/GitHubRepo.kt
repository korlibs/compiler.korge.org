package korlibs.korge.kotlincompiler.git

import korlibs.korge.kotlincompiler.*
import korlibs.korge.kotlincompiler.util.*
import java.io.*

class GitHubRepoRefPath private constructor(val ref: GitHubRepoRef, val path: String, unit: Unit) {
    constructor(ref: GitHubRepoRef, path: String) : this(ref, sanitizePath(path), Unit)
    constructor(owner: String, name: String, ref: String, path: String) : this(GitHubRepoRef(owner, name, ref), path)

    val localFile by lazy {
        File(ref.repo.localCloneFile, "../__checkouts__/$path/${ref.ref}.tar.gz").absoluteFile
    }

    fun getArchive(pipes: StdPipes): File {
        if (!localFile.exists()) {
            localFile.parentFile.mkdirs()
            ProcessBuilder("git", "pull")
                .directory(ref.repo.getClonedRepo(pipes)).start().redirectToWaitFor(pipes)
            ProcessBuilder("git", "archive", "-o", localFile.absolutePath, ref.ref, path)
                .directory(ref.repo.getClonedRepo(pipes)).start().redirectToWaitFor(pipes)
        }
        return localFile
    }

    fun extractTo(folder: File, pipes: StdPipes) {
        TarTools(removeNDirs = path.count { it == '/' } + 1).extractTarGz(getArchive(pipes), folder)
    }

    companion object {
        fun sanitizePath(path: String): String = path.replace("\\", "/").replace("../", "/").trim('/')
    }
}

class GitHubRepoRef private constructor(val repo: GitHubRepo, val ref: String, unit: Unit) {
    constructor(repo: GitHubRepo, ref: String) : this(repo, File(ref).name, Unit)
    constructor(owner: String, name: String, ref: String) : this(GitHubRepo(owner, name), ref)

    val downloadTarGz by lazy {
        "https://github.com/${repo.owner}/${repo.name}/archive/refs/tags/$ref.tar.gz"
    }
    val downloadZip by lazy {
        "https://github.com/${repo.owner}/${repo.name}/archive/refs/tags/$ref.zip"
    }
}

class GitHubRepo private constructor(val owner: String, val name: String, unit: Unit) {

    constructor(owner: String, name: String) : this(File(owner).name, File(name).name, Unit)

    val gitUrl by lazy { "https://github.com/$owner/$name.git" }

    val localCloneFile by lazy {
        File(USER_HOME, ".kproject/clones/github.com/$owner/$name/__git__")
    }

    fun getClonedRepo(pipes: StdPipes): File {
        if (!File(localCloneFile, ".git").isDirectory) {
            localCloneFile.mkdirs()
            ProcessBuilder("git", "clone", gitUrl, localCloneFile.absolutePath)
                .start()
                .redirectToWaitFor(pipes)
        }
        return localCloneFile
    }

}