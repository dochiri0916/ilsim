package com.dochiri.ilsim.auth.adapter.`in`.web

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.HexFormat

object RunnerTokenFile {

    fun loadOrCreate(file: Path): String {
        read(file)?.let { return it }
        file.parent?.let { Files.createDirectories(it) }
        val token = HexFormat.of().formatHex(ByteArray(24).also(SecureRandom()::nextBytes))
        try {
            create(file, token)
        } catch (e: FileAlreadyExistsException) {
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
            Files.createFile(file)
        }
        Files.writeString(file, token)
    }
}
