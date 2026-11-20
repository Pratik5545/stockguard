package com.example.stockguard.common.config;

import com.example.stockguard.reservation.config.ReservationProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReservationProperties.class)
public class WorkflowConfig {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock clock() { return Clock.systemUTC(); }
}
