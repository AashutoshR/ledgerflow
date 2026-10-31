package dev.ledgerflow.payments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "transfers.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TransferWorker {
  private final Transfers transfers;
  private final LedgerClient ledger;

  public TransferWorker(Transfers transfers, LedgerClient ledger) {
    this.transfers = transfers;
    this.ledger = ledger;
  }

  @Scheduled(fixedDelayString = "${transfers.recovery-delay-ms:5000}")
  public void recover() {
    for (int i = 0; i < 10; i++) {
      var next = transfers.claim();
      if (next.isEmpty()) return;
      try {
        transfers.complete(next.get(), ledger.resolve(next.get()));
      } catch (RuntimeException unavailable) {
        transfers.defer(next.get());
      }
    }
  }
}
