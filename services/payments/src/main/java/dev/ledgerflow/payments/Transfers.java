package dev.ledgerflow.payments;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Transfers {
  private final JdbcTemplate db;

  public Transfers(JdbcTemplate db) {
    this.db = db;
  }

  public record Request(
      UUID sourceWalletId, UUID destinationWalletId, String amount, String currency) {}

  public record Transfer(
      UUID id,
      String actor,
      UUID sourceWalletId,
      UUID destinationWalletId,
      String amount,
      String currency,
      String status,
      UUID ledgerOperationId,
      String errorCode,
      String correlationId) {}

  private Transfer map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Transfer(
        rs.getObject("id", UUID.class),
        rs.getString("actor"),
        rs.getObject("source_id", UUID.class),
        rs.getObject("destination_id", UUID.class),
        BigDecimal.valueOf(rs.getLong("amount"), 2).toPlainString(),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getObject("ledger_operation_id", UUID.class),
        rs.getString("error_code"),
        rs.getString("correlation_id"));
  }

  @Transactional
  public Transfer create(String actor, String key, Request request, String correlation) {
    if (key == null || key.isBlank() || key.length() > 200)
      throw new PaymentException(400, "INVALID_IDEMPOTENCY_KEY");
    if (request.sourceWalletId() == null
        || request.destinationWalletId() == null
        || request.sourceWalletId().equals(request.destinationWalletId())
        || !"GBP".equals(request.currency())) throw new PaymentException(400, "INVALID_REQUEST");
    if (request.amount() == null || !request.amount().matches("[0-9]{1,15}\\.[0-9]{2}"))
      throw new PaymentException(400, "INVALID_AMOUNT");
    long amount = new BigDecimal(request.amount()).movePointRight(2).longValueExact();
    if (amount <= 0) throw new PaymentException(400, "INVALID_AMOUNT");
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO transfers(id,actor,idempotency_key,source_id,destination_id,amount,currency,status,correlation_id) VALUES (?,?,?,?,?,?,?,'CREATED',?) ON CONFLICT(actor,idempotency_key) DO NOTHING",
        id,
        actor,
        key,
        request.sourceWalletId(),
        request.destinationWalletId(),
        amount,
        request.currency(),
        correlation);
    Transfer existing =
        db.query(
                "SELECT * FROM transfers WHERE actor=? AND idempotency_key=?",
                this::map,
                actor,
                key)
            .getFirst();
    if (!existing.sourceWalletId().equals(request.sourceWalletId())
        || !existing.destinationWalletId().equals(request.destinationWalletId())
        || !existing.amount().equals(BigDecimal.valueOf(amount, 2).toPlainString())
        || !existing.currency().equals(request.currency()))
      throw new PaymentException(409, "IDEMPOTENCY_CONFLICT");
    return existing;
  }

  public Transfer get(String actor, UUID id) {
    return db.query("SELECT * FROM transfers WHERE id=? AND actor=?", this::map, id, actor).stream()
        .findFirst()
        .orElseThrow(() -> new PaymentException(404, "TRANSFER_NOT_FOUND"));
  }

  @Transactional
  public Optional<Transfer> claim() {
    // Lease expires even if this process crashes after ledger commit. The original reference is
    // reused.
    return db
        .query(
            """
      UPDATE transfers SET status='PROCESSING',attempts=attempts+1,updated_at=now(),
        next_attempt_at=now()+interval '30 seconds'
      WHERE id=(SELECT id FROM transfers WHERE status IN ('CREATED','PROCESSING') AND next_attempt_at<=now()
        ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING *
      """,
            this::map)
        .stream()
        .findFirst();
  }

  @Transactional
  public void complete(Transfer transfer, LedgerClient.Decision decision) {
    if (!transfer.id().equals(decision.reference())
        || !("SUCCEEDED".equals(decision.status()) || "FAILED".equals(decision.status()))
        || ("SUCCEEDED".equals(decision.status())
            && (decision.ledgerOperationId() == null || decision.errorCode() != null))
        || ("FAILED".equals(decision.status())
            && (decision.errorCode() == null || decision.ledgerOperationId() != null)))
      throw new IllegalArgumentException("Invalid ledger decision");
    int changed =
        db.update(
            "UPDATE transfers SET status=?,ledger_operation_id=?,error_code=?,updated_at=now() WHERE id=? AND status='PROCESSING'",
            decision.status(),
            decision.ledgerOperationId(),
            decision.errorCode(),
            transfer.id());
    if (changed == 0) return;
    db.update(
        "INSERT INTO workflow_attempts(id,transfer_id,outcome) VALUES (?,?,?)",
        UUID.randomUUID(),
        transfer.id(),
        decision.status());
    db.update(
        "INSERT INTO outbox(event_id,event_type,aggregate_id,correlation_id,payload) VALUES (?,'transfer.completed',?,?,jsonb_build_object('transfer_id',?::text,'status',?::text))",
        UUID.randomUUID(),
        transfer.id(),
        transfer.correlationId(),
        transfer.id(),
        decision.status());
  }

  @Transactional
  public void defer(Transfer transfer) {
    db.update(
        "UPDATE transfers SET next_attempt_at=now()+make_interval(secs => least(30,power(2,least(attempts,4))::int) + random()*3),updated_at=now() WHERE id=? AND status='PROCESSING'",
        transfer.id());
    db.update(
        "INSERT INTO workflow_attempts(id,transfer_id,outcome) VALUES (?,?,'RETRY_PENDING')",
        UUID.randomUUID(),
        transfer.id());
  }
}
