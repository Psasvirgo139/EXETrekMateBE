package com.trekmate.exe.ws;

import com.trekmate.exe.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtTokenProvider tokenProvider;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            var httpServletRequest = servletRequest.getServletRequest();

            // 1. Try Authorization header (Bearer <token>)
            String bearer = httpServletRequest.getHeader("Authorization");
            String token = null;

            if (bearer != null && bearer.startsWith("Bearer ")) {
                token = bearer.substring(7);
            }

            // 2. Fallback to query param ?token=
            if (token == null || token.isBlank()) {
                token = httpServletRequest.getParameter("token");
            }

            if (token != null && tokenProvider.validateToken(token)) {
                String userId = tokenProvider.getUserIdFromToken(token);
                attributes.put("userId", userId);
                log.info("WS Handshake accepted for userId={}", userId);
                return true;
            }
        }

        log.warn("WS Handshake rejected: Missing or invalid JWT token");
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
