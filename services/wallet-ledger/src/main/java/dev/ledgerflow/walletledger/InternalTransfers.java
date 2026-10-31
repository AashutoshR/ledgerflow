package dev.ledgerflow.walletledger;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InternalTransfers {
  private final Ledger ledger;
  private final JdbcTemplate db;

  public InternalTransfers(Ledger ledger, JdbcTemplate db) {
    this.ledger = ledger;
    this.db = db;
  }

  public record Request(
      String actor,
      UUID sourceWalletId,
      UUID destinationWalletId,
      String amount,
      String currency) {}

  public record Decision(UUID reference, String status, UUID ledgerOperationId, String errorCode) {}

  public Decision lookup(UUID reference) {
    return db
        .query(
            "SELECT * FROM transfer_decisions WHERE reference=?",
            (rs, n) ->
                new Decision(
                    reference,
                    rs.getString("error_code") == null ? "SUCCEEDED" : "FAILED",
                    rs.getObject("ledger_operation_id", UUID.class),
                    rs.getString("error_code")),
            reference)
        .stream()
        .findFirst()
        .orElseThrow(() -> new LedgerException(404, "OPERATION_NOT_FOUND"));
  }

  @Transactional
  public Decision post(UUID reference, Request request, String correlation) {
    if (request.actor() == null
        || request.actor().isBlank()
        || request.sourceWalletId() == null
        || request.destinationWalletId() == null) throw new LedgerException(400, "INVALID_REQUEST");
    Money.parse(request.amount());
    if (!"GBP".equals(request.currency())) throw new LedgerException(400, "INVALID_REQUEST");
    db.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
        Boolean.class,
        "decision:" + reference);
    var old = db.queryForList("SELECT * FROM transfer_decisions WHERE reference=?", reference);
    if (!old.isEmpty()) {
      var row = old.getFirst();
      if (!Objects.equals(row.get("actor"), request.actor())
          || !Objects.equals(row.get("source_id"), request.sourceWalletId())
          || !Objects.equals(row.get("destination_id"), request.destinationWalletId())
          || !Objects.equals(row.get("amount"), request.amount())
          || !Objects.equals(row.get("currency"), request.currency()))
        throw new LedgerException(409, "OPERATION_CONFLICT");
      return lookup(reference);
    }
    // Execute without a nested transactional proxy: business rejections are persisted in this
    // transaction, while any database failure rolls back the decision and financial writes
    // together.
    return ledger.decide(reference, request, correlation);
  }
}
