package com.trekmate.exe.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trekmate.exe.dto.response.MemberListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages SSE connections per tour.
 * When any tour mutation happens (join / end), this broadcaster pushes
 * the updated state to every connected device in that tour.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TourEventBroadcaster {

    /** tourId → list of active SSE emitters (one per connected device) */
    private final Map<String, List<SseEmitter>> registry = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    /**
     * Register a new SSE connection for a tour.
     * Cleanup callbacks ensure dead emitters are removed automatically.
     */
    public SseEmitter register(String tourId) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = no timeout
        List<SseEmitter> list = registry.computeIfAbsent(tourId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        emitter.onCompletion(() -> remove(tourId, emitter));
        emitter.onTimeout(() -> remove(tourId, emitter));
        emitter.onError(e -> remove(tourId, emitter));

        log.debug("SSE registered: tourId={} total={}", tourId, list.size());
        return emitter;
    }

    /**
     * Push updated member list to all devices in the tour.
     * Called after a member joins.
     */
    public void broadcastMemberUpdate(String tourId, MemberListResponse response) {
        broadcast(tourId, "member_update", response);
    }

    /**
     * Push tour_ended event then close all SSE connections for the tour.
     * Called when the leader ends the tour.
     */
    public void broadcastTourEnded(String tourId) {
        broadcast(tourId, "tour_ended", Map.of("tour_id", tourId));
        List<SseEmitter> list = registry.remove(tourId);
        if (list != null) {
            list.forEach(SseEmitter::complete);
        }
    }

    private void broadcast(String tourId, String eventName, Object data) {
        List<SseEmitter> list = registry.get(tourId);
        if (list == null || list.isEmpty()) return;

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) {
            list.removeAll(dead);
            log.debug("SSE removed {} dead emitters for tourId={}", dead.size(), tourId);
        }
    }

    /**
     * Send a heartbeat comment to every active emitter every 25 seconds.
     * This prevents Render's nginx reverse proxy (55s idle timeout) from
     * closing the connection, and helps detect dead clients faster.
     */
    @Scheduled(fixedDelay = 25_000)
    public void sendHeartbeats() {
        if (registry.isEmpty()) return;
        registry.forEach((tourId, list) -> {
            List<SseEmitter> dead = new ArrayList<>();
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException e) {
                    dead.add(emitter);
                }
            }
            if (!dead.isEmpty()) {
                list.removeAll(dead);
                log.debug("Heartbeat removed {} dead emitters for tourId={}", dead.size(), tourId);
            }
        });
    }

    private void remove(String tourId, SseEmitter emitter) {
        List<SseEmitter> list = registry.get(tourId);
        if (list != null) list.remove(emitter);
    }
}
