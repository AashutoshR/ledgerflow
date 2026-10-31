package dev.ledgerflow.workspace;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

class PortalIdentityIT {
  @Test
  void importedRealmProvidesSignedVerifiedEmailClaimsForPortal() throws Exception {
    var json = new ObjectMapper();
    JsonNode realm;
    try (var in = getClass().getResourceAsStream("/realm.json")) {
      assertThat(in).isNotNull();
      realm = json.readTree(in);
    }
    // Password grants are enabled only in this disposable test realm to inspect real browser-client
    // claims.
    for (var client : realm.path("clients"))
      if (client.path("clientId").asText().equals("ledgerflow-web"))
        ((ObjectNode) client).put("directAccessGrantsEnabled", true);
    var users = ((ObjectNode) realm).putArray("users");
    for (boolean verified : new boolean[] {true, false}) {
      var user = users.addObject();
      user.put("username", verified ? "verified-client" : "unverified-client");
      user.put("email", verified ? "verified@example.test" : "unverified@example.test");
      user.put("firstName", "Portal");
      user.put("lastName", "Client");
      user.put("enabled", true);
      user.put("emailVerified", verified);
      user.putArray("credentials")
          .addObject()
          .put("type", "password")
          .put("value", "disposable-test-password")
          .put("temporary", false);
    }
    try (var keycloak =
        new GenericContainer<>("quay.io/keycloak/keycloak:26.4.1")
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "disposable-test-admin")
            .withCopyToContainer(
                Transferable.of(json.writeValueAsBytes(realm)),
                "/opt/keycloak/data/import/realm.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(
                Wait.forHttp("/realms/ledgerflow/.well-known/openid-configuration")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)))) {
      keycloak.start();
      String issuer =
          "http://"
              + keycloak.getHost()
              + ":"
              + keycloak.getMappedPort(8080)
              + "/realms/ledgerflow";
      var decoder = org.springframework.security.oauth2.jwt.JwtDecoders.fromIssuerLocation(issuer);
      for (boolean verified : new boolean[] {true, false}) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "ledgerflow-web");
        form.add("username", verified ? "verified-client" : "unverified-client");
        form.add("password", "disposable-test-password");
        form.add("scope", "openid profile email");
        var response =
            RestClient.create()
                .post()
                .uri(issuer + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        var token = decoder.decode(response.path("access_token").asText());
        assertThat(token.getSubject()).isNotBlank();
        assertThat(token.getClaimAsString("email"))
            .isEqualTo(verified ? "verified@example.test" : "unverified@example.test");
        assertThat(token.getClaimAsBoolean("email_verified")).isEqualTo(verified);
      }
    }
  }
}
