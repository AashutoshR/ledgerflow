package dev.ledgerflow.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient
class HostedWebTest {
  @Autowired WebTestClient http;
  @MockitoBean ReactiveJwtDecoder decoder;

  @Test
  void publicPagesAndRuntimeConfigurationDoNotRequireLogin() {
    for (String route : new String[] {"/", "/workspace", "/portal", "/payments", "/callback"}) {
      http.get()
          .uri(route)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(
              body ->
                  org.assertj.core.api.Assertions.assertThat(body)
                      .contains("LedgerFlow hosted shell"));
    }
    http.get()
        .uri("/config.js")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body ->
                org.assertj.core.api.Assertions.assertThat(body)
                    .contains("\"demoEnabled\":false", "oidcAuthority")
                    .doesNotContain("password", "clientSecret"));
  }

  @Test
  void applicationDataRequiresAuthenticationAndInternalEndpointsAreNotRouted() {
    http.get().uri("/api/v1/workspace").exchange().expectStatus().isUnauthorized();
    http.get().uri("/internal/v1/transfers/test").exchange().expectStatus().isUnauthorized();
    http.get().uri("/actuator/health").exchange().expectStatus().isOk();
  }
}
