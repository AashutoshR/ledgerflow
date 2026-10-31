package dev.ledgerflow.walletledger;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@Testcontainers
class LedgerIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired Ledger ledger;
  @Autowired JdbcTemplate db;
  @Autowired WalletQueries wallets;
  @Autowired InternalTransfers internal;
  String actor;
  UUID a, b;

  @BeforeEach
  void setup() {
    actor = UUID.randomUUID().toString();
    a = ledger.create(actor).id();
    b = ledger.create(UUID.randomUUID().toString()).id();
    ledger.post(actor, "fund", "FUNDING", null, a, "10.00", "GBP", "test");
  }

  @Test
  void postingIsBalancedAndRetrySafe() {
    var first = ledger.post(actor, "transfer", "TRANSFER", a, b, "2.50", "GBP", "test");
    assertThat(ledger.post(actor, "transfer", "TRANSFER", a, b, "2.50", "GBP", "test"))
        .isEqualTo(first);
    assertThat(ledger.balance(actor, a).balance()).isEqualTo("7.50");
    assertThat(
            db.queryForObject(
                "SELECT sum(amount) FROM entries WHERE journal_id=?", Long.class, first.id()))
        .isZero();
    assertThatThrownBy(
            () -> ledger.post(actor, "transfer", "TRANSFER", a, b, "3.00", "GBP", "test"))
        .isInstanceOf(LedgerException.class)
        .hasMessage("IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(() -> db.update("DELETE FROM entries WHERE journal_id=?", first.id()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void rejectedPaymentRollsBackAndOwnershipIsRequired() {
    assertThatThrownBy(
            () -> ledger.post(actor, "too-much", "TRANSFER", a, b, "11.00", "GBP", "test"))
        .hasMessage("INSUFFICIENT_FUNDS");
    assertThat(ledger.balance(actor, a).balance()).isEqualTo("10.00");
    assertThatThrownBy(() -> ledger.balance("someone-else", a)).hasMessage("WALLET_NOT_FOUND");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM posting_operations WHERE actor=?", Integer.class, actor))
        .isEqualTo(1);
  }

  @Test
  void concurrentSpendingCannotOverdraw() throws Exception {
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(12)) {
      var futures = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 12; i++) {
        String key = "spend-" + i;
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    ledger.post(actor, key, "TRANSFER", a, b, "2.00", "GBP", "test");
                    return true;
                  } catch (LedgerException e) {
                    if (!e.code.equals("INSUFFICIENT_FUNDS")) throw e;
                    return false;
                  }
                }));
      }
      gate.countDown();
      int successes = 0;
      for (var f : futures) if (f.get(30, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(5);
      assertThat(ledger.balance(actor, a).balance()).isEqualTo("0.00");
    }
  }

  @Test
  void concurrentRetriesPostOnce() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<Future<Ledger.Operation>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(() -> ledger.post(actor, "same", "TRANSFER", a, b, "1.00", "GBP", "test")));
      var ids = new HashSet<UUID>();
      for (var f : futures) ids.add(f.get(30, TimeUnit.SECONDS).id());
      assertThat(ids).hasSize(1);
      assertThat(ledger.balance(actor, a).balance()).isEqualTo("9.00");
    }
  }

  @Test
  void historyIsSignedPaginatedAndOwnerOnly() {
    var transfer = ledger.post(actor, "history", "TRANSFER", a, b, "2.00", "GBP", "test");
    var first = wallets.history(actor, a, 0, 1);
    assertThat(first.hasNext()).isTrue();
    assertThat(first.items().getFirst().operationId()).isEqualTo(transfer.id());
    assertThat(first.items().getFirst().amount()).isEqualTo("-2.00");
    assertThat(wallets.history(actor, a, 1, 1).items().getFirst().amount()).isEqualTo("10.00");
    assertThatThrownBy(() -> wallets.history("other", a, 0, 20)).hasMessage("WALLET_NOT_FOUND");
    assertThatThrownBy(() -> wallets.history(actor, a, 0, 101)).hasMessage("INVALID_PAGINATION");
  }

  @Test
  void frozenWalletRejectsNewPostingsButPreservesCompletedRetries() {
    var result = ledger.post(actor, "before-freeze", "TRANSFER", a, b, "1.00", "GBP", "test");
    wallets.freeze("admin", a, true, "review", "audit-test");
    assertThatThrownBy(
            () -> ledger.post(actor, "after-freeze", "TRANSFER", a, b, "1.00", "GBP", "test"))
        .hasMessage("WALLET_FROZEN");
    assertThat(ledger.post(actor, "before-freeze", "TRANSFER", a, b, "1.00", "GBP", "test"))
        .isEqualTo(result);
    assertThat(ledger.balance(actor, a).balance()).isEqualTo("9.00");
    wallets.freeze("admin", a, false, "review complete", "audit-test");
    ledger.post(actor, "after-freeze", "TRANSFER", a, b, "1.00", "GBP", "test");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM wallet_audit WHERE wallet_id=?", Integer.class, a))
        .isEqualTo(2);
    assertThatThrownBy(() -> db.update("DELETE FROM wallet_audit WHERE wallet_id=?", a))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void freezeHoldingWalletLockWinsAgainstConcurrentPosting() throws Exception {
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                Objects.requireNonNull(db.getDataSource())));
    var frozen = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var freeze =
          pool.submit(
              () ->
                  transaction.execute(
                      s -> {
                        wallets.freeze("admin", a, true, "race test", "race");
                        frozen.countDown();
                        try {
                          if (!release.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("Timed out waiting for posting");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new RuntimeException(e);
                        }
                        return null;
                      }));
      try {
        assertThat(frozen.await(10, TimeUnit.SECONDS)).isTrue();
        var posting =
            pool.submit(() -> ledger.post(actor, "race", "TRANSFER", a, b, "1.00", "GBP", "race"));
        release.countDown();
        freeze.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> posting.get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(LedgerException.class)
            .hasRootCauseMessage("WALLET_FROZEN");
        assertThat(ledger.balance(actor, a).balance()).isEqualTo("10.00");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void existingVersionOneDatabaseUpgradesWithoutLosingWallets() {
    String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var initial =
        org.flywaydb.core.Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .target("1")
            .cleanDisabled(false)
            .load();
    try {
      initial.migrate();
      UUID wallet = UUID.randomUUID();
      db.update(
          "INSERT INTO " + schema + ".wallets(id,owner_id) VALUES (?,?)", wallet, "migration-user");
      var upgraded =
          org.flywaydb.core.Flyway.configure()
              .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
              .schemas(schema)
              .defaultSchema(schema)
              .load();
      assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(2);
      assertThat(
              db.queryForObject(
                  "SELECT owner_id FROM " + schema + ".wallets WHERE id=?", String.class, wallet))
          .isEqualTo("migration-user");
      assertThat(upgraded.info().current().getVersion().toString()).isEqualTo("3");
    } finally {
      initial.clean();
    }
  }

  @Test
  void internalDecisionsSurviveLostResponsesAndDoNotChangeAfterFunding() {
    UUID reference = UUID.randomUUID();
    var request = new InternalTransfers.Request(actor, a, b, "2.00", "GBP");
    var first = internal.post(reference, request, "test");
    assertThat(internal.lookup(reference)).isEqualTo(first);
    assertThat(internal.post(reference, request, "retry")).isEqualTo(first);
    assertThat(ledger.balance(actor, a).balance()).isEqualTo("8.00");
    assertThatThrownBy(
            () ->
                internal.post(
                    reference, new InternalTransfers.Request(actor, a, b, "3.00", "GBP"), "test"))
        .hasMessage("OPERATION_CONFLICT");
    UUID rejected = UUID.randomUUID();
    var tooLarge = new InternalTransfers.Request(actor, a, b, "20.00", "GBP");
    assertThat(internal.post(rejected, tooLarge, "test").errorCode())
        .isEqualTo("INSUFFICIENT_FUNDS");
    ledger.post(actor, "more-funds", "FUNDING", null, a, "100.00", "GBP", "test");
    assertThat(internal.post(rejected, tooLarge, "retry").errorCode())
        .isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(ledger.balance(actor, a).balance()).isEqualTo("108.00");
  }

  @Test
  void databaseRejectsUnbalancedJournal() {
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                Objects.requireNonNull(db.getDataSource())));
    assertThatThrownBy(
            () ->
                tx.execute(
                    s -> {
                      UUID id = UUID.randomUUID();
                      db.update(
                          "INSERT INTO posting_operations(id,actor,idempotency_key,kind,destination_id,amount,currency) VALUES (?,?,?,'FUNDING',?,100,'GBP')",
                          id,
                          actor,
                          "bad",
                          a);
                      db.update("INSERT INTO journals(id,currency) VALUES (?,'GBP')", id);
                      db.update(
                          "INSERT INTO entries(journal_id,account_id,amount) VALUES (?,?,100)",
                          id,
                          a);
                      return null;
                    }))
        .isInstanceOf(org.springframework.transaction.TransactionSystemException.class)
        .rootCause()
        .hasMessageContaining("Unbalanced journal");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM posting_operations WHERE actor=? AND idempotency_key='bad'",
                Integer.class,
                actor))
        .isZero();
  }
}
