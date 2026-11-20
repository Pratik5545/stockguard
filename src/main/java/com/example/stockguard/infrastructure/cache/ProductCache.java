package com.example.stockguard.infrastructure.cache;

import com.example.stockguard.catalog.dto.ProductMetadata;
import com.example.stockguard.infrastructure.redis.RedisFeaturesProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProductCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final RedisFeaturesProperties settings;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public ProductCache(StringRedisTemplate redis, ObjectMapper json, RedisFeaturesProperties settings,
                        MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.settings = settings;
        hits = meters.counter("stockguard.cache.product.hits");
        misses = meters.counter("stockguard.cache.product.misses");
        errors = meters.counter("stockguard.cache.product.errors");
    }

    public boolean enabled() { return settings.enabled(); }

    public Optional<ProductMetadata> get(UUID id, long version) {
        try {
            String value = redis.opsForValue().get(key(id, version));
            if (value != null) {
                var product = json.readValue(value, ProductMetadata.class);
                if (product != null && id.equals(product.id()) && product.catalogVersion() == version) {
                    hits.increment();
                    return Optional.of(product);
                }
            }
        } catch (DataAccessException | JacksonException exception) {
            errors.increment();
        }
        misses.increment();
        return Optional.empty();
    }

    public void put(ProductMetadata product) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { write(product); }
            });
        } else {
            write(product);
        }
    }

    private void write(ProductMetadata product) {
        try {
            redis.opsForValue().set(key(product.id(), product.catalogVersion()),
                    json.writeValueAsString(product), settings.cacheTtl());
        } catch (DataAccessException | JacksonException exception) {
            errors.increment();
        }
    }

    private String key(UUID id, long version) {
        return settings.namespace() + ":product:v1:" + id + ":" + version;
    }
}
