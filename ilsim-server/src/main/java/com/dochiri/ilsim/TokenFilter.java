package com.dochiri.ilsim;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 임시 단일 토큰 인증. Spring Security 도입 전까지 /api/**, /runner/** 를 보호한다. */
@Component
class TokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenFilter.class);

    private final String token;

    TokenFilter(@Value("${ilsim.token:}") String configured) {
        if (configured.isBlank()) {
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            configured = HexFormat.of().formatHex(bytes);
            log.warn("ILSIM_TOKEN 미설정 - 임시 토큰 생성: {}", configured);
        }
        this.token = configured;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean protectedPath = path.startsWith("/runner/") || (path.startsWith("/api/") && !path.equals("/api/status"));
        return !protectedPath;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader("X-Ilsim-Token");
        if (given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(req, res);
        } else {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
