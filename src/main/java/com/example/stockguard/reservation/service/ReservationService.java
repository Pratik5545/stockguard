package com.example.stockguard.reservation.service;

import com.example.stockguard.common.response.PageResponse;
import com.example.stockguard.idempotency.*;
import com.example.stockguard.order.dto.OrderResponse;
import com.example.stockguard.order.repository.OrderRepository;
import com.example.stockguard.reservation.config.ReservationProperties;
import com.example.stockguard.reservation.dto.*;
import com.example.stockguard.reservation.repository.ReservationRepository;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class ReservationService {
    private final ReservationRepository reservations;
    private final OrderRepository orders;
    private final IdempotencyService idempotency;
    private final ReservationProperties settings;
    private final Clock clock;

    public ReservationService(ReservationRepository reservations, OrderRepository orders,
                              IdempotencyService idempotency, ReservationProperties settings, Clock clock) {
        this.reservations = reservations; this.orders = orders; this.idempotency = idempotency;
        this.settings = settings; this.clock = clock;
    }

    public IdempotentResult<ReservationResponse> create(UUID user, String key, CreateReservationRequest request) {
        lockUser(user);
        int quantity = request.quantity().intValueExact();
        return idempotency.execute(user, "CREATE_RESERVATION", key, request.productId() + ":" + quantity,
                201, ReservationResponse.class, () -> {
                    if (quantity > settings.maxQuantity()) throw error(HttpStatus.BAD_REQUEST,
                            "Quantity exceeds the per-reservation limit of " + settings.maxQuantity() + ".");
                    if (reservations.pendingCount(user) >= settings.maxPending()) throw error(HttpStatus.CONFLICT,
                            "Pending reservation limit reached.");
                    var product = reservations.lockProduct(request.productId())
                            .filter(ReservationRepository.ProductSnapshot::active)
                            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Active product not found."));
                    var stock = reservations.lockStock(product.id());
                    if (stock.onHand() - stock.reserved() < quantity) throw error(HttpStatus.CONFLICT, "Insufficient stock.");
                    Instant now = now();
                    var response = new ReservationResponse(UUID.randomUUID(), user, "PENDING", product.id(),
                            product.sku(), product.name(), quantity, product.price(),
                            now.plus(settings.holdDuration()).truncatedTo(ChronoUnit.MICROS), now, now);
                    reservations.writeStock(product.id(), stock.onHand(), stock.reserved() + quantity, now);
                    reservations.insert(response);
                    return response;
                });
    }

    public IdempotentResult<OrderResponse> confirm(UUID user, UUID id, String key) {
        lockUser(user);
        return idempotency.execute(user, "CONFIRM_RESERVATION", key, id.toString(), 201, OrderResponse.class, () -> {
            var reservation = ownedPending(user, id);
            var stock = reservations.lockStock(reservation.productId());
            Instant now = now();
            requireBeforeDeadline(reservation, now);
            requireHeldStock(stock, reservation.quantity());
            reservations.writeStock(reservation.productId(), stock.onHand() - reservation.quantity(),
                    stock.reserved() - reservation.quantity(), now);
            reservations.transition(id, "CONFIRMED", now);
            var order = new OrderResponse(UUID.randomUUID(), id, user, "PLACED", reservation.productId(),
                    reservation.sku(), reservation.productName(), reservation.quantity(), reservation.unitPrice(),
                    reservation.unitPrice().multiply(BigDecimal.valueOf(reservation.quantity())), now);
            orders.insert(order);
            return order;
        });
    }

    public IdempotentResult<ReservationResponse> cancel(UUID user, UUID id, String key) {
        lockUser(user);
        return idempotency.execute(user, "CANCEL_RESERVATION", key, id.toString(), 200, ReservationResponse.class, () -> {
            var reservation = ownedPending(user, id);
            var stock = reservations.lockStock(reservation.productId());
            Instant now = now();
            requireBeforeDeadline(reservation, now);
            requireHeldStock(stock, reservation.quantity());
            reservations.writeStock(reservation.productId(), stock.onHand(), stock.reserved() - reservation.quantity(), now);
            reservations.transition(id, "CANCELLED", now);
            return reservations.find(id, false).orElseThrow();
        });
    }

    @Transactional(readOnly = true)
    public ReservationResponse get(UUID user, boolean admin, UUID id) {
        return reservations.find(id, false).filter(value -> admin || value.userId().equals(user))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Reservation not found."));
    }

    @Transactional(readOnly = true)
    public PageResponse<ReservationResponse> list(UUID user, int page, int size) {
        PageResponse.validate(page, size);
        return PageResponse.of(reservations.list(user, page, size), page, size, reservations.count(user));
    }

    private void lockUser(UUID user) {
        if (!reservations.lockUser(user)) throw error(HttpStatus.UNAUTHORIZED, "Account no longer exists.");
    }

    private ReservationResponse ownedPending(UUID user, UUID id) {
        var value = reservations.find(id, true).filter(reservation -> reservation.userId().equals(user))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Reservation not found."));
        if (!value.status().equals("PENDING")) throw error(HttpStatus.CONFLICT, "Reservation is no longer pending.");
        return value;
    }

    private static void requireBeforeDeadline(ReservationResponse value, Instant now) {
        if (!now.isBefore(value.expiresAt())) throw error(HttpStatus.CONFLICT, "Reservation deadline has passed.");
    }

    static void requireHeldStock(ReservationRepository.Stock stock, int quantity) {
        if (stock.reserved() < quantity || stock.onHand() < quantity) {
            throw new IllegalStateException("Reservation inventory is inconsistent.");
        }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
