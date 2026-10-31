package dev.ledgerflow.payments;

import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

@Component
public class LedgerClient {
  public record Decision(UUID reference, String status, UUID ledgerOperationId, String errorCode) {}

  private final RestClient http;
  private final String base, tokenUrl, clientId, secret;

  public LedgerClient(
      @Value("${ledger.base-url}") String base,
      @Value("${ledger.token-url}") String tokenUrl,
      @Value("${ledger.client-id}") String clientId,
      @Value("${ledger.client-secret}") String secret) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(3));
    this.http = RestClient.builder().requestFactory(factory).build();
    this.base = base;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.secret = secret;
  }

  public Decision resolve(Transfers.Transfer transfer) {
    String token = token();
    String endpoint = base + "/internal/v1/transfers/" + transfer.id();
    try {
      return http.get()
          .uri(endpoint)
          .headers(h -> h.setBearerAuth(token))
          .retrieve()
          .body(Decision.class);
    } catch (HttpClientErrorException.NotFound missing) {
      return http.put()
          .uri(endpoint)
          .headers(h -> h.setBearerAuth(token))
          .header("X-Request-ID", transfer.correlationId())
          .body(
              Map.of(
                  "actor",
                  transfer.actor(),
                  "sourceWalletId",
                  transfer.sourceWalletId(),
                  "destinationWalletId",
                  transfer.destinationWalletId(),
                  "amount",
                  transfer.amount(),
                  "currency",
                  transfer.currency()))
          .retrieve()
          .body(Decision.class);
    }
  }

  private String token() {
    if (secret.isBlank())
      throw new IllegalStateException("Payments service credentials are not configured");
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", secret);
    form.add("scope", "ledger.post");
    var response =
        http.post()
            .uri(tokenUrl)
            .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(com.fasterxml.jackson.databind.JsonNode.class);
    if (response == null || !response.path("access_token").isTextual())
      throw new IllegalStateException("Missing service token");
    return response.path("access_token").asText();
  }
}
