package dev.ledgerflow.workspace;

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
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (req, res, error) -> securityError(req, res, 401, "UNAUTHENTICATED"))
                    .accessDeniedHandler(
                        (req, res, error) -> securityError(req, res, 403, "FORBIDDEN")))
        .build();
  }

  private void securityError(
      jakarta.servlet.http.HttpServletRequest req,
      jakarta.servlet.http.HttpServletResponse res,
      int status,
      String code)
      throws java.io.IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    if (status == 401) res.setHeader("WWW-Authenticate", "Bearer");
    new com.fasterxml.jackson.databind.ObjectMapper()
        .writeValue(
            res.getOutputStream(),
            java.util.Map.of(
                "code", code, "requestId", String.valueOf(req.getAttribute("requestId"))));
  }
}
