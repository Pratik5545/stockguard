package com.example.stockguard.order.service;

import com.example.stockguard.common.response.PageResponse;
import com.example.stockguard.order.dto.OrderResponse;
import com.example.stockguard.order.repository.OrderRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orders;
    public OrderService(OrderRepository orders) { this.orders = orders; }

    public OrderResponse get(UUID user, boolean admin, UUID id) {
        return orders.find(id).filter(order -> admin || order.userId().equals(user))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found."));
    }

    public PageResponse<OrderResponse> list(UUID user, int page, int size) {
        PageResponse.validate(page, size);
        return PageResponse.of(orders.list(user, page, size), page, size, orders.count(user));
    }
}
