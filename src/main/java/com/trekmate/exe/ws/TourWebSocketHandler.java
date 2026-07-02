package com.trekmate.exe.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trekmate.exe.dto.response.MemberListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages WebSocket connections per tour and broadcasts events to all
 * connected devices. WebSockets bypass nginx/Cloudflare response buffering,
 * so events arrive at every client immediately.
 *
 * URI pattern:  wss://host/exe/tours/{tourId}/ws
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TourWebSocketHandler extends TextWebSocketHandler {

    /** tourId → active WebSocket sessions (one per connected device) */
    private final Map<String, List<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    // ── Connection lifecycle ─────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String tourId = tourIdFrom(session);
        if (tourId == null) {
            log.warn("WS handshake missing tourId — closing");
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        sessions.computeIfAbsent(tourId, k -> new CopyOnWriteArrayList<>()).add(session);
        log.info("WS connected: tourId={} sessionId={} total={}", tourId, session.getId(),
                sessions.get(tourId).size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String tourId = tourIdFrom(session);
        if (tourId != null) {
            List<WebSocketSession> list = sessions.get(tourId);
            if (list != null) list.remove(session);
        }
        log.info("WS closed: sessionId={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.warn("WS transport error sessionId={}: {}", session.getId(), ex.getMessage());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    // ── Public broadcast API ─────────────────────────────────────────────────

    /** Push updated member list to every device connected to the tour. */
    public void broadcastMemberUpdate(String tourId, MemberListResponse response) {
        try {
            List<Map<String, Object>> memberList = response.members().stream()
                    .map(m -> Map.<String, Object>of("user_id", m.userId(), "is_leader", m.isLeader()))
                    .toList();
            String json = objectMapper.writeValueAsString(
                    Map.of("type", "member_update", "members", memberList));
            broadcast(tourId, json);
        } catch (Exception e) {
            log.error("WS broadcastMemberUpdate error: {}", e.getMessage());
        }
    }

    /** Push tour_ended event then close every session for the tour. */
    public void broadcastTourEnded(String tourId) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", "tour_ended"));
            broadcast(tourId, json);
        } catch (Exception e) {
            log.error("WS broadcastTourEnded error: {}", e.getMessage());
        }
        List<WebSocketSession> list = sessions.remove(tourId);
        if (list != null) list.forEach(s -> closeQuietly(s, CloseStatus.NORMAL));
    }

    // ── Heartbeat (keep-alive through Cloudflare 55s idle timeout) ───────────

    @Scheduled(fixedDelay = 25_000)
    public void sendHeartbeats() {
        if (sessions.isEmpty()) return;
        sessions.forEach((tourId, list) -> {
            List<WebSocketSession> dead = new ArrayList<>();
            for (WebSocketSession s : list) {
                try {
                    if (s.isOpen()) s.sendMessage(new TextMessage("{\"type\":\"heartbeat\"}"));
                    else dead.add(s);
                } catch (IOException e) {
                    dead.add(s);
                }
            }
            if (!dead.isEmpty()) {
                list.removeAll(dead);
                log.debug("WS heartbeat pruned {} dead session(s) for tourId={}", dead.size(), tourId);
            }
        });
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void broadcast(String tourId, String json) {
        List<WebSocketSession> list = sessions.get(tourId);
        int count = list == null ? 0 : list.size();
        log.info("WS broadcast type='{}' → tourId={} ({} session(s))",
                typeOf(json), tourId, count);
        if (count == 0) return;

        List<WebSocketSession> dead = new ArrayList<>();
        for (WebSocketSession s : list) {
            try {
                if (s.isOpen()) s.sendMessage(new TextMessage(json));
                else dead.add(s);
            } catch (IOException e) {
                log.warn("WS send failed sessionId={}: {}", s.getId(), e.getMessage());
                dead.add(s);
            }
        }
        if (!dead.isEmpty()) list.removeAll(dead);
    }

    /**
     * Extracts {tourId} by parsing the URI path:
     *   /exe/tours/{tourId}/ws  →  "tourId" is the segment after "tours"
     */
    private String tourIdFrom(WebSocketSession session) {
        if (session.getUri() == null) return null;
        String[] parts = session.getUri().getPath().split("/");
        // parts = ["", "exe", "tours", "<tourId>", "ws"]
        for (int i = 0; i < parts.length - 1; i++) {
            if ("tours".equals(parts[i]) && i + 1 < parts.length) {
                String candidate = parts[i + 1];
                if (!candidate.isEmpty() && !"ws".equals(candidate)) return candidate;
            }
        }
        return null;
    }

    private String typeOf(String json) {
        try { return objectMapper.readTree(json).path("type").asText("?"); }
        catch (Exception e) { return "?"; }
    }

    private void closeQuietly(WebSocketSession s, CloseStatus status) {
        try { if (s.isOpen()) s.close(status); } catch (IOException ignored) { }
    }
}
