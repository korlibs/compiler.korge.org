package korlibs.korge.kotlincompiler.util

import io.methvin.watcher.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.*
import kotlin.io.path.*

object RecursiveDirectoryWatcher {
    abstract class AwaitableCloseable : AutoCloseable {
        abstract fun await()
    }

    fun watch(files: Iterable<File>, pipes: StdPipes, event: (File) -> Unit): AwaitableCloseable {
        val paths = files.map { it.canonicalFile.toPath() }
        //val afile = file.canonicalFile
        pipes.out.println("Watching for $paths")
        val watcher = DirectoryWatcher.builder()
            //.path(afile.toPath())
            .paths(paths)
            .listener { event: DirectoryChangeEvent ->
                //println("event=$event")
                when (event.eventType()) {
                    DirectoryChangeEvent.EventType.CREATE -> {
                        event(event.path().toFile())
                    }
                    DirectoryChangeEvent.EventType.MODIFY -> {
                        event(event.path().toFile())
                    }
                    DirectoryChangeEvent.EventType.DELETE -> {
                        event(event.path().toFile())
                    }
                    DirectoryChangeEvent.EventType.OVERFLOW -> Unit
                }
            }
            .fileTreeVisitor { file, onDirectory, onFile ->
                var directoryCount = 0
                //println("Start visiting $file : directories=$directoryCount")
                Files.walkFileTree(file, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val baseName = dir.name
                        //println("preVisitDirectory: $dir : '${baseName}'")

                        if (baseName.startsWith(".") || (dir.pathString.endsWith("build") && !dir.pathString.contains("src"))) {
                            //println("!!!!!!!! SKIP SUBTREE")
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        onDirectory.call(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        onFile.call(file)
                        //println("visitFile: $file")
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                        //println("visitFileFailed: $file")
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        //println("postVisitDirectory: $dir")
                        directoryCount++
                        return FileVisitResult.CONTINUE
                    }
                })
                //println("Completed visiting $file : directories=$directoryCount")
            }
            // .fileHashing(false) // defaults to true
            // .logger(logger) // defaults to LoggerFactory.getLogger(DirectoryWatcher.class)
            //.watchService(FileSystems.getDefault().newWatchService()) // defaults based on OS to either JVM WatchService or the JNA macOS WatchService
            .build()

        val future = watcher.watchAsync()
        //val future = watcher.watch()

        return object : AwaitableCloseable() {
            override fun await() {
                future.get()
            }
            override fun close() = watcher.close()
        }
    }
}