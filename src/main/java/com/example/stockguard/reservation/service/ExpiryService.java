package com.example.stockguard.reservation.service;

import com.example.stockguard.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpiryService {
    private final ReservationRepository reservations;
    private final Clock clock;
    public ExpiryService(ReservationRepository reservations, Clock clock) {
        this.reservations = reservations; this.clock = clock;
    }

    // One claim per transaction avoids holding multiple inventory locks across a batch.
    @Transactional
    public boolean expireNext() {
        var id = reservations.claimDue(clock.instant());
        if (id.isEmpty()) return false;
        var reservation = reservations.find(id.orElseThrow(), false).orElseThrow();
        var stock = reservations.lockStock(reservation.productId());
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(reservation.expiresAt())) return false;
        ReservationService.requireHeldStock(stock, reservation.quantity());
        reservations.writeStock(reservation.productId(), stock.onHand(), stock.reserved() - reservation.quantity(), now);
        reservations.transition(reservation.id(), "EXPIRED", now);
        return true;
    }
}
