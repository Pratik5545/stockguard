package com.example.stockguard.infrastructure.redis;

import com.example.stockguard.infrastructure.ratelimit.RateLimitFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RedisFeaturesProperties.class)
public class RedisFeaturesConfig {
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        // The filter runs only inside Spring Security, after authorization.
        registration.setEnabled(false);
        return registration;
    }
}
