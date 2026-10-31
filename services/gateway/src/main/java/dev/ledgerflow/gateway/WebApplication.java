package dev.ledgerflow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.*;

@Configuration
class WebApplication {
  @Bean
  RouterFunction<ServerResponse> pages() {
    return RouterFunctions.route(
        RequestPredicates.GET("/")
            .or(RequestPredicates.GET("/workspace"))
            .or(RequestPredicates.GET("/portal"))
            .or(RequestPredicates.GET("/payments"))
            .or(RequestPredicates.GET("/callback")),
        request ->
            ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(BodyInserters.fromResource(new ClassPathResource("static/index.html"))));
  }

  @RestController
  static class RuntimeConfig {
    private final String issuer;
    private final boolean demo;
    private final ObjectMapper json;

    RuntimeConfig(
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${app.demo-enabled:false}") boolean demo,
        ObjectMapper json) {
      this.issuer = issuer;
      this.demo = demo;
      this.json = json;
    }

    @GetMapping(value = "/config.js", produces = "application/javascript")
    String config() throws Exception {
      return "window.__LEDGERFLOW_CONFIG__="
          + json.writeValueAsString(Map.of("oidcAuthority", issuer, "demoEnabled", demo))
          + ";";
    }
  }
}
