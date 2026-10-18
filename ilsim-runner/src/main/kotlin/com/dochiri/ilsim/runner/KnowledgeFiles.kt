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

/**
 * `~/.ilsim/projects/<id>/` 의 규칙·메모리 파일은 서버(웹 승인)와 Runner 가 함께 고친다.
 * 읽고-고치고-쓰는 동안 프로젝트 디렉터리의 `.lock` 을 OS 파일 잠금으로 잡아 서로의 변경을 덮어쓰지 않게 한다(서버 `FileKnowledgeStore` 와 같은 규약).
 * 같은 JVM 의 스레드끼리는 파일 잠금이 겹칠 수 없어 디렉터리별 [ReentrantLock] 을 먼저 잡는다.
 */
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

    /** 임시 파일에 쓴 뒤 옮겨서 다른 프로세스가 반쯤 쓰인 파일을 읽지 않게 한다. */
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
