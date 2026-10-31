package dev.ledgerflow.payments;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = "transfers.worker-enabled=false")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
class TransfersIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired Transfers transfers;
  @Autowired JdbcTemplate db;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @MockitoBean LedgerClient ledger;
  String actor;
  Transfers.Request request;

  @BeforeEach
  void setup() {
    db.update("DELETE FROM outbox");
    db.update("DELETE FROM workflow_attempts");
    db.update("DELETE FROM transfers");
    actor = UUID.randomUUID().toString();
    request = new Transfers.Request(UUID.randomUUID(), UUID.randomUUID(), "10.00", "GBP");
  }

  @Test
  void concurrentRequestsKeepOneDurableOperationAndConflictOnChanges() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<Future<Transfers.Transfer>>();
      for (int i = 0; i < 8; i++)
        futures.add(pool.submit(() -> transfers.create(actor, "same", request, "request-id")));
      var ids = new HashSet<UUID>();
      for (var f : futures) ids.add(f.get(10, TimeUnit.SECONDS).id());
      assertThat(ids).hasSize(1);
    }
    assertThatThrownBy(
            () ->
                transfers.create(
                    actor,
                    "same",
                    new Transfers.Request(
                        request.sourceWalletId(), request.destinationWalletId(), "11.00", "GBP"),
                    "test"))
        .hasMessage("IDEMPOTENCY_CONFLICT");
    var transfer = transfers.create(actor, "same", request, "test");
    assertThatThrownBy(() -> transfers.get("other", transfer.id()))
        .hasMessage("TRANSFER_NOT_FOUND");
  }

  @Test
  void responseLostAfterLedgerCommitRecoversOriginalReference() {
    var transfer = transfers.create(actor, "lost-response", request, "request-id");
    UUID posted = UUID.randomUUID();
    var calls = new AtomicInteger();
    // First call represents a committed ledger posting whose response never reached payments.
    when(ledger.resolve(any()))
        .thenAnswer(
            invocation -> {
              Transfers.Transfer received = invocation.getArgument(0);
              assertThat(received.id()).isEqualTo(transfer.id());
              if (calls.getAndIncrement() == 0)
                throw new org.springframework.web.client.ResourceAccessException("response lost");
              return new LedgerClient.Decision(transfer.id(), "SUCCEEDED", posted, null);
            });
    new TransferWorker(transfers, ledger).recover();
    assertThat(transfers.get(actor, transfer.id()).status()).isEqualTo("PROCESSING");
    db.update("UPDATE transfers SET next_attempt_at=now() WHERE id=?", transfer.id());
    new TransferWorker(transfers, ledger).recover();
    assertThat(transfers.get(actor, transfer.id()).ledgerOperationId()).isEqualTo(posted);
    assertThat(transfers.get(actor, transfer.id()).status()).isEqualTo("SUCCEEDED");
    assertThat(db.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(1);
    assertThat(transfers.create(actor, "lost-response", request, "retry").id())
        .isEqualTo(transfer.id());
  }

  @Test
  void publicApiReturnsPendingLocationAndOwnerOnlyStatus() throws Exception {
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/transfers")
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.jwt()
                            .jwt(j -> j.subject(actor)))
                    .header("Idempotency-Key", "api")
                    .contentType("application/json")
                    .content(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(request)))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isAccepted())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .exists("Location"))
            .andReturn();
    String location = response.getResponse().getHeader("Location");
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(j -> j.subject("other"))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(j -> j.subject(actor))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status")
                .value("CREATED"));
  }

  @Test
  void durableLedgerRejectionIsTerminalAndLeaseRecoversWorkerCrash() {
    var transfer = transfers.create(actor, "reject", request, "test");
    assertThat(transfers.claim()).isPresent();
    assertThat(transfers.claim()).isEmpty();
    db.update("UPDATE transfers SET next_attempt_at=now() WHERE id=?", transfer.id());
    when(ledger.resolve(any()))
        .thenReturn(new LedgerClient.Decision(transfer.id(), "FAILED", null, "INSUFFICIENT_FUNDS"));
    new TransferWorker(transfers, ledger).recover();
    var failed = transfers.get(actor, transfer.id());
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(transfers.claim()).isEmpty();
    assertThat(transfers.create(actor, "reject", request, "again").status()).isEqualTo("FAILED");
  }
}
