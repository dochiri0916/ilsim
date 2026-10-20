package com.dochiri.ilsim.auth.adapter.`in`.web

import com.dochiri.ilsim.auth.domain.LoginToken
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component

@Component
class RememberCookie(@Value("\${server.servlet.session.cookie.secure:false}") private val secure: Boolean) {

    fun read(req: HttpServletRequest): String? = req.cookies?.firstOrNull { it.name == NAME }?.value

    fun write(res: HttpServletResponse, value: String) = set(res, value, LoginToken.LIFETIME.seconds)

    fun clear(res: HttpServletResponse) = set(res, "", 0)

    private fun set(res: HttpServletResponse, value: String, maxAge: Long) {
        val cookie = ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(maxAge).build()
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }

    companion object {
        const val NAME = "ILSIM_REMEMBER"
    }
}
