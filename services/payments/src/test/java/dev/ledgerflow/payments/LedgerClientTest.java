package dev.ledgerflow.payments;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LedgerClientTest {
  @Test
  void recoveryQueriesCommittedDecisionAfterUncertainPostResponse() throws Exception {
    UUID reference = UUID.randomUUID(), posting = UUID.randomUUID();
    var recorded = new AtomicBoolean();
    var posts = new AtomicInteger();
    var authorization = new AtomicReference<String>();
    var tokenForm = new AtomicReference<String>();
    var payload = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        exchange -> {
          tokenForm.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = "{\"access_token\":\"service-token\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/internal/v1/transfers/" + reference,
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          int status;
          String body;
          if (exchange.getRequestMethod().equals("PUT")) {
            payload.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            posts.incrementAndGet();
            recorded.set(true);
            status = 503;
            body = "{}"; // Commit succeeded, but the caller receives no confirmed outcome.
          } else if (recorded.get()) {
            status = 200;
            body =
                "{\"reference\":\""
                    + reference
                    + "\",\"status\":\"SUCCEEDED\",\"ledgerOperationId\":\""
                    + posting
                    + "\",\"errorCode\":null}";
          } else {
            status = 404;
            body = "{}";
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var client = new LedgerClient(base, base + "/token", "payments-service", "test-secret");
      var transfer =
          new Transfers.Transfer(
              reference,
              "alice",
              UUID.randomUUID(),
              UUID.randomUUID(),
              "10.00",
              "GBP",
              "PROCESSING",
              null,
              null,
              UUID.randomUUID().toString());
      assertThatThrownBy(() -> client.resolve(transfer))
          .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
      assertThat(client.resolve(transfer).ledgerOperationId()).isEqualTo(posting);
      assertThat(posts.get()).isEqualTo(1);
      assertThat(authorization.get()).isEqualTo("Bearer service-token");
      assertThat(tokenForm.get())
          .contains(
              "grant_type=client_credentials", "scope=ledger.post", "client_id=payments-service");
      assertThat(payload.get()).contains("alice", "10.00", transfer.sourceWalletId().toString());
    } finally {
      server.stop(0);
    }
  }
}
