package com.trekmate.exe.controller;

import com.trekmate.exe.dto.request.CreateTourRequest;
import com.trekmate.exe.dto.request.EndTourRequest;
import com.trekmate.exe.dto.request.JoinTourRequest;
import com.trekmate.exe.dto.response.CreateTourResponse;
import com.trekmate.exe.dto.response.EndTourResponse;
import com.trekmate.exe.dto.response.JoinTourResponse;
import com.trekmate.exe.dto.response.MemberListResponse;
import com.trekmate.exe.service.ExeTourService;
import com.trekmate.exe.sse.TourEventBroadcaster;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/exe/tours")
@RequiredArgsConstructor
@Tag(name = "TrekMate EXE Tours", description = "Tour execution APIs for the TrekMate Android app")
public class ExeTourController {

    private final ExeTourService tourService;
    private final TourEventBroadcaster broadcaster;

    @PostMapping
    @Operation(summary = "Create a new tour")
    public ResponseEntity<CreateTourResponse> createTour(@Valid @RequestBody CreateTourRequest request) {
        return ResponseEntity.ok(tourService.createTour(request));
    }

    @PostMapping("/join")
    @Operation(summary = "Join an existing tour")
    public ResponseEntity<JoinTourResponse> joinTour(@Valid @RequestBody JoinTourRequest request) {
        JoinTourResponse response = tourService.joinTour(request);
        // Broadcast AFTER the @Transactional service method returns (transaction committed).
        // This avoids a race where the push fires before the new member is visible in the DB.
        broadcaster.broadcastMemberUpdate(response.tourId(), new MemberListResponse(response.members()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/end")
    @Operation(summary = "End a tour — leader only")
    public ResponseEntity<EndTourResponse> endTour(@Valid @RequestBody EndTourRequest request) {
        EndTourResponse response = tourService.endTour(request);
        // Broadcast AFTER the @Transactional service method returns (transaction committed).
        broadcaster.broadcastTourEnded(request.tourId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tourId}/members")
    @Operation(summary = "Get current member list")
    public ResponseEntity<MemberListResponse> getMembers(@PathVariable String tourId) {
        return ResponseEntity.ok(tourService.getMembers(tourId));
    }

    /**
     * SSE endpoint — devices subscribe here after creating/joining a tour.
     * Register the emitter FIRST, then query current state. This ordering
     * ensures we never miss a broadcast that fires between registration and
     * the initial-state send (the duplicate is harmless; Android is idempotent).
     */
    @GetMapping(value = "/{tourId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to tour events (SSE)",
               description = "Streams member_update and tour_ended events. Keeps connection alive until tour ends.")
    public SseEmitter subscribeToEvents(@PathVariable String tourId, HttpServletResponse response) {
        // Disable nginx buffering on Render so events are delivered immediately
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store");
        response.setHeader("Connection", "keep-alive");

        // Register FIRST so we cannot miss broadcasts that fire while we query the DB.
        SseEmitter emitter = broadcaster.register(tourId);

        // Send current member list immediately so the subscriber is up to date.
        // Queried AFTER registration — guarantees freshest committed state.
        try {
            MemberListResponse currentState = tourService.getMembers(tourId);
            emitter.send(SseEmitter.event()
                    .name("member_update")
                    .data(currentState, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }
}
