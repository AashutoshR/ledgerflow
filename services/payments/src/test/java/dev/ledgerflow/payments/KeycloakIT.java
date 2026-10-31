package dev.ledgerflow.payments;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

class KeycloakIT {
  @Test
  void importedRealmIssuesScopedAudienceRestrictedServiceToken() throws Exception {
    var json = new ObjectMapper();
    JsonNode realm;
    try (var input = getClass().getResourceAsStream("/realm.json")) {
      assertThat(input).isNotNull();
      realm = json.readTree(input);
    }
    for (var client : realm.path("clients"))
      if (client.path("clientId").asText().equals("payments-service"))
        ((ObjectNode) client).put("secret", "integration-only-secret");
    try (var keycloak =
        new GenericContainer<>("quay.io/keycloak/keycloak:26.4.1")
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "integration-only-admin")
            .withCopyToContainer(
                Transferable.of(json.writeValueAsBytes(realm)),
                "/opt/keycloak/data/import/realm.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(
                Wait.forHttp("/realms/ledgerflow/.well-known/openid-configuration")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)))) {
      keycloak.start();
      String base = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
      var form = new LinkedMultiValueMap<String, String>();
      form.add("grant_type", "client_credentials");
      form.add("client_id", "payments-service");
      form.add("client_secret", "integration-only-secret");
      form.add("scope", "ledger.post");
      var token =
          RestClient.create()
              .post()
              .uri(base + "/realms/ledgerflow/protocol/openid-connect/token")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
      assertThat(token).isNotNull();
      var verified =
          org.springframework.security.oauth2.jwt.JwtDecoders.fromIssuerLocation(
                  base + "/realms/ledgerflow")
              .decode(token.path("access_token").asText());
      assertThat(verified.getAudience()).contains("ledger-internal");
      JsonNode claims =
          json.readTree(
              new String(
                  Base64.getUrlDecoder()
                      .decode(token.path("access_token").asText().split("\\.")[1]),
                  StandardCharsets.UTF_8));
      assertThat(claims.path("scope").asText().split(" ")).contains("ledger.post");
      assertThat(claims.path("azp").asText()).isEqualTo("payments-service");
      JsonNode audience = claims.path("aud");
      assertThat(audience.isArray() ? audience.toString() : audience.asText())
          .contains("ledger-internal");
      assertThat(claims.path("iss").asText()).isEqualTo(base + "/realms/ledgerflow");
      assertThat(claims.path("realm_access").path("roles").toString())
          .doesNotContain("administrator");
    }
  }
}
