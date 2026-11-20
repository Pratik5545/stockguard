package com.example.stockguard.order.controller;

import com.example.stockguard.common.response.PageResponse;
import com.example.stockguard.order.dto.OrderResponse;
import com.example.stockguard.order.service.OrderService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OrderController {
    private final OrderService orders;
    public OrderController(OrderService orders) { this.orders = orders; }

    @GetMapping("/orders/{id}")
    public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orders.get(UUID.fromString(jwt.getSubject()), "ADMIN".equals(jwt.getClaimAsString("role")), id);
    }

    @GetMapping("/me/orders")
    public PageResponse<OrderResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return orders.list(UUID.fromString(jwt.getSubject()), page, size);
    }
}
