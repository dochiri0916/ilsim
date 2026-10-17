package com.dochiri.ilsim.runner

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunnerTokenTest {
    private val dir = Files.createTempDirectory("ilsim-token")
    private val file = dir.resolve("token")

    @Test
    fun `환경 변수가 있으면 파일보다 우선한다`() {
        file.writeText("from-file\n")
        assertEquals("from-env", RunnerToken.resolve("from-env", file))
    }

    @Test
    fun `환경 변수가 없으면 서버가 만든 파일을 읽고 줄바꿈은 버린다`() {
        file.writeText("from-file\n")
        assertEquals("from-file", RunnerToken.resolve(null, file))
        assertEquals("from-file", RunnerToken.resolve("  ", file))
    }

    @Test
    fun `파일도 환경 변수도 없거나 파일이 비어 있으면 null`() {
        assertNull(RunnerToken.resolve(null, file))
        file.writeText("\n")
        assertNull(RunnerToken.resolve(null, file))
    }
}
