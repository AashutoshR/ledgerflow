package dev.ledgerflow.identity;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class Security {
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> {}))
        .build();
  }
}
