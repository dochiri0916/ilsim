package com.dochiri.ilsim.auth.adapter.`in`.web

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.HexFormat

/**
 * Runner 와 나누는 토큰 파일(`~/.ilsim/token`). 없으면 만들고, 있으면 그대로 읽는다.
 * 같은 PC 의 Runner 가 이 파일을 읽으므로 환경 변수를 맞출 필요가 없다. 소유자만 읽고 쓰게 만든다.
 */
object RunnerTokenFile {

    fun loadOrCreate(file: Path): String {
        read(file)?.let { return it }
        file.parent?.let { Files.createDirectories(it) }
        val token = HexFormat.of().formatHex(ByteArray(24).also(SecureRandom()::nextBytes))
        try {
            create(file, token)
        } catch (e: FileAlreadyExistsException) {
            // 비어 있거나 다른 프로세스가 먼저 만든 경우: 읽을 수 있으면 그 값을, 비어 있으면 덮어쓴다.
            return read(file) ?: token.also { Files.writeString(file, it) }
        }
        return token
    }

    private fun read(file: Path): String? =
        if (Files.isRegularFile(file)) Files.readString(file).trim().takeIf { it.isNotEmpty() } else null

    private fun create(file: Path, token: String) {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (e: UnsupportedOperationException) {
            Files.createFile(file) // POSIX 권한이 없는 파일 시스템
        }
        Files.writeString(file, token)
    }
}
