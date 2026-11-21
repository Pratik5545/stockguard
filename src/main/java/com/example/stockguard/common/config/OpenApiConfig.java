package com.example.stockguard.common.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI stockGuardApi() {
        return new OpenAPI().info(new Info().title("StockGuard API").version("v1")
                .description("Inventory, temporary checkout reservations and orders. "
                        + "Login for a bearer token. Reservation create/confirm/cancel require an Idempotency-Key. "
                        + "Default holds last 5 minutes; maximum 2 units and 3 pending holds per customer. "
                        + "Only owners may confirm/cancel; admins may read. Currency: INR. No payment processing."))
                .addServersItem(new Server().url("/"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
