package com.trekmate.exe.controller;

import com.trekmate.exe.dto.request.CreateTourRequest;
import com.trekmate.exe.dto.request.EndTourRequest;
import com.trekmate.exe.dto.request.JoinTourRequest;
import com.trekmate.exe.dto.response.CreateTourResponse;
import com.trekmate.exe.dto.response.EndTourResponse;
import com.trekmate.exe.dto.response.JoinTourResponse;
import com.trekmate.exe.dto.response.MemberListResponse;
import com.trekmate.exe.service.ExeTourService;
import com.trekmate.exe.ws.TourWebSocketHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/exe/tours")
@RequiredArgsConstructor
@Tag(name = "TrekMate EXE Tours", description = "Tour execution APIs for the TrekMate Android app")
public class ExeTourController {

    private final ExeTourService tourService;
    private final TourWebSocketHandler wsHandler;     // WebSocket real-time channel

    @PostMapping
    @Operation(summary = "Create a new tour")
    public ResponseEntity<CreateTourResponse> createTour(@Valid @RequestBody CreateTourRequest request) {
        return ResponseEntity.ok(tourService.createTour(request));
    }

    @PostMapping("/join")
    @Operation(summary = "Join an existing tour")
    public ResponseEntity<JoinTourResponse> joinTour(@Valid @RequestBody JoinTourRequest request) {
        JoinTourResponse response = tourService.joinTour(request);
        // Broadcast AFTER @Transactional commits — WebSocket channel.
        MemberListResponse memberList = new MemberListResponse(response.members());
        wsHandler.broadcastMemberUpdate(response.tourId(), memberList);    // WebSocket
        return ResponseEntity.ok(response);
    }

    @PostMapping("/end")
    @Operation(summary = "End a tour — leader only")
    public ResponseEntity<EndTourResponse> endTour(@Valid @RequestBody EndTourRequest request) {
        EndTourResponse response = tourService.endTour(request);
        // Broadcast AFTER @Transactional commits — WebSocket channel.
        wsHandler.broadcastTourEnded(request.tourId());     // WebSocket
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tourId}/members")
    @Operation(summary = "Get current member list")
    public ResponseEntity<MemberListResponse> getMembers(@PathVariable String tourId) {
        return ResponseEntity.ok(tourService.getMembers(tourId));
    }
}

