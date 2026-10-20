package com.dochiri.ilsim.runner

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories

object KnowledgeFiles {
    private val local = ConcurrentHashMap<Path, ReentrantLock>()

    fun <T> locked(dir: Path, action: () -> T): T {
        dir.createDirectories()
        val key = dir.toAbsolutePath().normalize()
        val lock = local.computeIfAbsent(key) { ReentrantLock() }
        return lock.withLock {
            if (lock.holdCount > 1) return@withLock action()
            FileChannel.open(key.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
                ch.lock().use { action() }
            }
        }
    }

    fun write(path: Path, text: String) {
        path.parent.createDirectories()
        val tmp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        try {
            Files.writeString(tmp, text)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    const val LOCK = ".lock"
}
