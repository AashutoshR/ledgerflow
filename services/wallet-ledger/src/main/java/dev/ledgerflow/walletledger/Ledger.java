package dev.ledgerflow.walletledger;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Ledger {
  private static final UUID TREASURY = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final JdbcTemplate db;

  public Ledger(JdbcTemplate db) {
    this.db = db;
  }

  public record Wallet(UUID id, String balance, String currency) {}

  public record Operation(UUID id, String status, String amount, String currency) {}

  InternalTransfers.Decision decide(
      UUID reference, InternalTransfers.Request request, String correlation) {
    UUID operation = null;
    String error = null;
    try {
      operation =
          post(
                  request.actor(),
                  "transfer:" + reference,
                  "TRANSFER",
                  request.sourceWalletId(),
                  request.destinationWalletId(),
                  request.amount(),
                  request.currency(),
                  correlation)
              .id();
    } catch (LedgerException rejection) {
      // Domain checks occur before financial writes. Database errors must escape and roll back.
      error = rejection.code;
    }
    db.update(
        "INSERT INTO transfer_decisions(reference,actor,source_id,destination_id,amount,currency,ledger_operation_id,error_code) VALUES (?,?,?,?,?,?,?,?)",
        reference,
        request.actor(),
        request.sourceWalletId(),
        request.destinationWalletId(),
        request.amount(),
        request.currency(),
        operation,
        error);
    return new InternalTransfers.Decision(
        reference, error == null ? "SUCCEEDED" : "FAILED", operation, error);
  }

  @Transactional
  public Wallet create(String owner) {
    UUID id = UUID.randomUUID();
    var ids =
        db.query(
            "INSERT INTO wallets(id,owner_id) VALUES (?,?) ON CONFLICT(owner_id) DO NOTHING RETURNING id",
            (rs, n) -> rs.getObject(1, UUID.class),
            id,
            owner);
    if (!ids.isEmpty()) {
      db.update("INSERT INTO ledger_accounts(id,wallet_id) VALUES (?,?)", id, id);
      db.update("INSERT INTO account_balances(account_id) VALUES (?)", id);
    } else id = db.queryForObject("SELECT id FROM wallets WHERE owner_id=?", UUID.class, owner);
    return balance(owner, id);
  }

  public Wallet balance(String actor, UUID id) {
    requireOwner(actor, id);
    return new Wallet(
        id,
        Money.format(
            db.queryForObject(
                "SELECT amount FROM account_balances WHERE account_id=?", Long.class, id)),
        "GBP");
  }

  private void requireOwner(String actor, UUID wallet) {
    if (!Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM wallets WHERE id=? AND owner_id=?)",
            Boolean.class,
            wallet,
            actor))) throw new LedgerException(404, "WALLET_NOT_FOUND");
  }

  @Transactional
  public Operation post(
      String actor,
      String key,
      String kind,
      UUID source,
      UUID destination,
      String amount,
      String currency,
      String correlation) {
    long minor = Money.parse(amount);
    if (key == null || key.isBlank() || key.length() > 200)
      throw new LedgerException(400, "INVALID_IDEMPOTENCY_KEY");
    if (!"GBP".equals(currency)
        || destination == null
        || !("TRANSFER".equals(kind) || "FUNDING".equals(kind)))
      throw new LedgerException(400, "INVALID_REQUEST");
    if ("TRANSFER".equals(kind)) {
      if (source == null || source.equals(destination))
        throw new LedgerException(400, "INVALID_SOURCE");
      requireOwner(actor, source);
    } else {
      if (source != null) throw new LedgerException(400, "INVALID_SOURCE");
      requireOwner(actor, destination);
    }
    // Serialize retries before reading the existing operation, including simultaneous first
    // requests.
    db.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
        Boolean.class,
        actor + ":" + key);
    var existing =
        db.queryForList(
            "SELECT * FROM posting_operations WHERE actor=? AND idempotency_key=?", actor, key);
    if (!existing.isEmpty()) {
      var old = existing.getFirst();
      if (!Objects.equals(old.get("source_id"), source)
          || !Objects.equals(old.get("destination_id"), destination)
          || !old.get("kind").equals(kind)
          || ((Number) old.get("amount")).longValue() != minor
          || !old.get("currency").equals(currency))
        throw new LedgerException(409, "IDEMPOTENCY_CONFLICT");
      return new Operation((UUID) old.get("id"), "SUCCEEDED", Money.format(minor), currency);
    }
    UUID debit = source == null ? TREASURY : source;
    // Freeze updates must acquire this same wallet row lock. Accounts always lock in UUID order.
    var walletIds = new ArrayList<UUID>();
    if (source != null) walletIds.add(source);
    walletIds.add(destination);
    walletIds.sort(UUID::compareTo);
    for (UUID id : walletIds) {
      var rows = db.queryForList("SELECT frozen FROM wallets WHERE id=? FOR UPDATE", id);
      if (rows.isEmpty()) throw new LedgerException(404, "WALLET_NOT_FOUND");
      if (Boolean.TRUE.equals(rows.getFirst().get("frozen")))
        throw new LedgerException(409, "WALLET_FROZEN");
    }
    var accounts = new ArrayList<>(List.of(debit, destination));
    accounts.sort(UUID::compareTo);
    for (UUID id : accounts)
      db.queryForObject(
          "SELECT amount FROM account_balances WHERE account_id=? FOR UPDATE", Long.class, id);
    long available =
        db.queryForObject(
            "SELECT amount FROM account_balances WHERE account_id=?", Long.class, debit);
    if (source != null && available < minor) throw new LedgerException(409, "INSUFFICIENT_FUNDS");
    UUID op = UUID.randomUUID();
    db.update(
        "INSERT INTO posting_operations(id,actor,idempotency_key,kind,source_id,destination_id,amount,currency) VALUES (?,?,?,?,?,?,?,?)",
        op,
        actor,
        key,
        kind,
        source,
        destination,
        minor,
        currency);
    db.update("INSERT INTO journals(id,currency) VALUES (?,?)", op, currency);
    db.update(
        "INSERT INTO entries(journal_id,account_id,amount) VALUES (?,?,?),(?,?,?)",
        op,
        debit,
        -minor,
        op,
        destination,
        minor);
    db.update("UPDATE account_balances SET amount=amount-? WHERE account_id=?", minor, debit);
    db.update("UPDATE account_balances SET amount=amount+? WHERE account_id=?", minor, destination);
    db.update(
        "INSERT INTO outbox(event_id,event_type,aggregate_id,correlation_id,payload) VALUES (?,'ledger.posted',?,?,jsonb_build_object('operation_id',?::text,'amount',?::text,'currency','GBP'))",
        UUID.randomUUID(),
        op,
        correlation,
        op,
        Money.format(minor));
    return new Operation(op, "SUCCEEDED", Money.format(minor), currency);
  }
}
