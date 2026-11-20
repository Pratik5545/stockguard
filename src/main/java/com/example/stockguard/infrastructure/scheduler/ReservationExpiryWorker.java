package com.example.stockguard.infrastructure.scheduler;

import com.example.stockguard.reservation.config.ReservationProperties;
import com.example.stockguard.reservation.service.ExpiryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "stockguard.reservation.expiry-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationExpiryWorker.class);
    private final ExpiryService expiry;
    private final ReservationProperties settings;
    private final MeterRegistry metrics;

    public ReservationExpiryWorker(ExpiryService expiry, ReservationProperties settings, MeterRegistry metrics) {
        this.expiry = expiry; this.settings = settings; this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${stockguard.reservation.expiry-interval-ms:5000}",
               initialDelayString = "${stockguard.reservation.expiry-interval-ms:5000}")
    public void runBatch() {
        try {
            for (int i = 0; i < settings.expiryBatchSize(); i++) {
                if (!expiry.expireNext()) break;
                metrics.counter("stockguard.reservations.expired").increment();
            }
        } catch (RuntimeException exception) {
            metrics.counter("stockguard.reservations.expiry.failures").increment();
            LOG.error("Reservation expiry batch failed; pending work will be retried.", exception);
        }
    }
}
