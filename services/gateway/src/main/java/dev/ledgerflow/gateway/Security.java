package dev.ledgerflow.gateway;

import org.springframework.context.annotation.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
class Security {
  @Bean
  SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity h) {
    return h.csrf(c -> c.disable())
        .authorizeExchange(
            a ->
                a.pathMatchers("/actuator/health/**", "/actuator/health")
                    .permitAll()
                    .pathMatchers("/api/**")
                    .authenticated()
                    .pathMatchers(
                        "/",
                        "/index.html",
                        "/config.js",
                        "/assets/**",
                        "/workspace",
                        "/portal",
                        "/payments",
                        "/callback",
                        "/favicon.ico")
                    .permitAll()
                    .anyExchange()
                    .denyAll())
        .oauth2ResourceServer(o -> o.jwt(j -> {}))
        .build();
  }
}
