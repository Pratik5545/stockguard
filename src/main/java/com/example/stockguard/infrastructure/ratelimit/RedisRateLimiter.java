package com.example.stockguard.infrastructure.ratelimit;

import com.example.stockguard.infrastructure.redis.RedisFeaturesProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisRateLimiter {
    private static final RedisScript<Long> SCRIPT = RedisScript.of(
            new ClassPathResource("redis/rate-limit.lua"), Long.class);
    private final StringRedisTemplate redis;
    private final RedisFeaturesProperties settings;
    private final MeterRegistry meters;

    public RedisRateLimiter(StringRedisTemplate redis, RedisFeaturesProperties settings, MeterRegistry meters) {
        this.redis = redis;
        this.settings = settings;
        this.meters = meters;
    }

    public Decision check(String scope, String identity, int limit) {
        if (!settings.enabled()) return new Decision(true, 0);
        try {
            Long retry = redis.execute(SCRIPT, List.of(settings.namespace() + ":rate:" + scope + ":" + identity),
                    Integer.toString(limit), Long.toString(settings.rateWindow().toMillis()));
            if (retry != null && retry >= 0) {
                meters.counter("stockguard.rate.requests", "scope", scope,
                        "result", retry == 0 ? "allowed" : "rejected").increment();
                return new Decision(true, retry);
            }
        } catch (DataAccessException exception) {
            // Fail closed without exposing Redis connection details in the API.
        }
        meters.counter("stockguard.rate.requests", "scope", scope, "result", "unavailable").increment();
        return new Decision(false, 1);
    }

    public record Decision(boolean available, long retryAfterSeconds) {}
}
