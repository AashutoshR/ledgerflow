package dev.ledgerflow.walletledger;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletQueries {
  private final JdbcTemplate db;

  public WalletQueries(JdbcTemplate db) {
    this.db = db;
  }

  public record Transaction(
      UUID operationId, String kind, String amount, String currency, Instant occurredAt) {}

  public record Page(List<Transaction> items, int page, int size, boolean hasNext) {}

  public record FreezeState(UUID walletId, boolean frozen) {}

  @Transactional(readOnly = true)
  public Page history(String owner, UUID wallet, int page, int size) {
    if (page < 0 || page > 100000 || size < 1 || size > 100)
      throw new LedgerException(400, "INVALID_PAGINATION");
    if (!Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM wallets WHERE id=? AND owner_id=?)",
            Boolean.class,
            wallet,
            owner))) throw new LedgerException(404, "WALLET_NOT_FOUND");
    var rows =
        db.query(
            """
        SELECT j.id, p.kind, e.amount, j.currency, j.created_at
        FROM entries e JOIN journals j ON j.id=e.journal_id
        JOIN posting_operations p ON p.id=j.id
        WHERE e.account_id=? ORDER BY j.created_at DESC, j.id DESC LIMIT ? OFFSET ?
        """,
            (rs, n) ->
                new Transaction(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    Money.format(rs.getLong(3)),
                    rs.getString(4),
                    rs.getTimestamp(5).toInstant()),
            wallet,
            size + 1,
            (long) page * size);
    return new Page(
        List.copyOf(rows.subList(0, Math.min(size, rows.size()))), page, size, rows.size() > size);
  }

  @Transactional
  public FreezeState freeze(
      String actor, UUID wallet, boolean frozen, String reason, String correlation) {
    if (reason == null || reason.isBlank() || reason.length() > 500)
      throw new LedgerException(400, "INVALID_FREEZE_REASON");
    var rows = db.queryForList("SELECT frozen FROM wallets WHERE id=? FOR UPDATE", wallet);
    if (rows.isEmpty()) throw new LedgerException(404, "WALLET_NOT_FOUND");
    // The same row lock is acquired before any posting checks wallet status.
    // Repeating the desired state remains safe and each admin request is audited.
    db.update("UPDATE wallets SET frozen=? WHERE id=?", frozen, wallet);
    db.update(
        "INSERT INTO wallet_audit(id,wallet_id,actor,action,reason,correlation_id) VALUES (?,?,?,?,?,?)",
        UUID.randomUUID(),
        wallet,
        actor,
        frozen ? "FREEZE" : "UNFREEZE",
        reason.trim(),
        correlation);
    return new FreezeState(wallet, frozen);
  }
}
