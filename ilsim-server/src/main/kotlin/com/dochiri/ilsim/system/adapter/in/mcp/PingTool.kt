package com.dochiri.ilsim.system.adapter.`in`.mcp

import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
class PingTool {

    @Tool(description = "Ilsim MCP 서버 연결 확인용. 항상 pong을 반환한다.")
    fun ping(): String = "pong"
}
