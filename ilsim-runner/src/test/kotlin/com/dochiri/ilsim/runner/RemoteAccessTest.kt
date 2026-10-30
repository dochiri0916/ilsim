package com.dochiri.ilsim.runner

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteAccessTest {
    @Test
    fun `원격 서버에는 Runner 토큰과 Cloudflare Access 서비스 토큰을 함께 보낸다`() {
        val seen = mutableMapOf<String, String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            listOf("X-Ilsim-Token", "CF-Access-Client-Id", "CF-Access-Client-Secret").forEach { h -> ex.requestHeaders.getFirst(h)?.let { seen[h] = it } }
            val body = "ok".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val home = Files.createTempDirectory("ilsim-access")
            RunnerAccess.save(home.resolve("access.json"), AccessToken("id-1", "secret-1"))
            val headers = RunnerAccess.headers(emptyMap(), home.resolve("access.json"))
            assertEquals("ok", ServerClient("http://127.0.0.1:${server.address.port}", "tok", headers).jevKeyStatus())
            assertEquals(mapOf("X-Ilsim-Token" to "tok", "CF-Access-Client-Id" to "id-1", "CF-Access-Client-Secret" to "secret-1"), seen.toMap())
            assertEquals(mapOf("CF-Access-Client-Id" to "e", "CF-Access-Client-Secret" to "s"),
                RunnerAccess.headers(mapOf("ILSIM_ACCESS_CLIENT_ID" to "e", "ILSIM_ACCESS_CLIENT_SECRET" to "s"), home.resolve("access.json")), "환경 변수가 파일보다 먼저다")
            assertTrue(RunnerAccess.headers(emptyMap(), home.resolve("none.json")).isEmpty(), "설정이 없으면 Access 헤더를 보내지 않는다")
            home.toFile().deleteRecursively()
        } finally {
            server.stop(0)
        }
    }
}
