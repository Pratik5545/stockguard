package com.example.stockguard.infrastructure.ratelimit;

import com.example.stockguard.infrastructure.redis.RedisFeaturesProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final RedisRateLimiter limiter;
    private final RedisFeaturesProperties settings;

    public RateLimitFilter(RedisRateLimiter limiter, RedisFeaturesProperties settings) {
        this.limiter = limiter;
        this.settings = settings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RedisRateLimiter.Decision decision = null;
        if (settings.enabled() && "POST".equals(request.getMethod())) {
            String path = request.getServletPath();
            if ("/api/v1/auth/login".equals(path) || "/api/v1/auth/register".equals(path)) {
                // Forwarded headers are not trusted; deployment must configure a trusted proxy explicitly.
                decision = limiter.check("auth", hash(request.getRemoteAddr()), settings.authLimit());
            } else if ("/api/v1/reservations".equals(path)) {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                decision = limiter.check("reservation", authentication.getName(), settings.reservationLimit());
            }
        }
        if (decision == null || decision.available() && decision.retryAfterSeconds() == 0) {
            chain.doFilter(request, response);
            return;
        }
        int status = decision.available() ? 429 : 503;
        String title = decision.available() ? "Too Many Requests" : "Service Unavailable";
        String detail = decision.available() ? "Request limit reached. Retry after the indicated delay."
                : "Request protection is temporarily unavailable. Retry later.";
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }

    private static String hash(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable.", exception);
        }
    }
}
