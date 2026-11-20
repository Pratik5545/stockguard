package com.example.stockguard.reservation.controller;

import com.example.stockguard.common.response.PageResponse;
import com.example.stockguard.idempotency.IdempotentResult;
import com.example.stockguard.order.dto.OrderResponse;
import com.example.stockguard.reservation.dto.*;
import com.example.stockguard.reservation.service.ReservationService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReservationController {
    private final ReservationService reservations;
    public ReservationController(ReservationService reservations) { this.reservations = reservations; }

    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateReservationRequest request) {
        var result = reservations.create(UUID.fromString(jwt.getSubject()), key, request);
        return response(result, "/api/v1/reservations/" + result.body().id());
    }

    @GetMapping("/reservations/{id}")
    public ReservationResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return reservations.get(UUID.fromString(jwt.getSubject()), "ADMIN".equals(jwt.getClaimAsString("role")), id);
    }

    @GetMapping("/me/reservations")
    public PageResponse<ReservationResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reservations.list(UUID.fromString(jwt.getSubject()), page, size);
    }

    @PostMapping("/reservations/{id}/confirm")
    public ResponseEntity<OrderResponse> confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                 @RequestHeader("Idempotency-Key") String key) {
        var result = reservations.confirm(UUID.fromString(jwt.getSubject()), id, key);
        return response(result, "/api/v1/orders/" + result.body().id());
    }

    @PostMapping("/reservations/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                       @RequestHeader("Idempotency-Key") String key) {
        return response(reservations.cancel(UUID.fromString(jwt.getSubject()), id, key), null);
    }

    private static <T> ResponseEntity<T> response(IdempotentResult<T> result, String location) {
        var builder = ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore())
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()));
        if (location != null) builder.location(URI.create(location));
        return builder.body(result.body());
    }
}
