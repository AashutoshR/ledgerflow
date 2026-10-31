package dev.ledgerflow.walletledger;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
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
                    .requestMatchers("/internal/**")
                    .access(
                        (authentication, context) -> {
                          var auth = authentication.get();
                          boolean allowed =
                              auth
                                      instanceof
                                      org.springframework.security.oauth2.server.resource
                                                  .authentication.JwtAuthenticationToken
                                              token
                                  && token.getAuthorities().stream()
                                      .anyMatch(
                                          authority ->
                                              authority.getAuthority().equals("SCOPE_ledger.post"))
                                  && token.getToken().getAudience() != null
                                  && token.getToken().getAudience().contains("ledger-internal")
                                  && "payments-service"
                                      .equals(token.getToken().getClaimAsString("azp"));
                          return new org.springframework.security.authorization
                              .AuthorizationDecision(allowed);
                        })
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("administrator")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> j.jwtAuthenticationConverter(authorities()))
                    .authenticationEntryPoint(
                        (req, res, e) -> securityError(req, res, 401, "UNAUTHENTICATED"))
                    .accessDeniedHandler(
                        (req, res, e) -> securityError(req, res, 403, "FORBIDDEN")))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
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
            Map.of("code", code, "requestId", String.valueOf(req.getAttribute("requestId"))));
  }

  private JwtAuthenticationConverter authorities() {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          var granted =
              new ArrayList<org.springframework.security.core.GrantedAuthority>(
                  new JwtGrantedAuthoritiesConverter().convert(jwt));
          Object realm = jwt.getClaims().get("realm_access");
          if (realm instanceof Map<?, ?> access
              && access.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles)
              if (role instanceof String name)
                granted.add(new SimpleGrantedAuthority("ROLE_" + name));
          }
          return granted;
        });
    return converter;
  }
}
