package dev.ledgerflow.walletledger;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class WalletController {
  private final Ledger ledger;
  private final boolean funding;

  WalletController(Ledger ledger, @Value("${ledger.test-funding-enabled:false}") boolean funding) {
    this.ledger = ledger;
    this.funding = funding;
  }

  record Posting(UUID sourceWalletId, UUID destinationWalletId, String amount, String currency) {}

  @PostMapping("/wallets")
  Ledger.Wallet create(@AuthenticationPrincipal Jwt user) {
    return ledger.create(user.getSubject());
  }

  @GetMapping("/wallets/{id}/balance")
  Ledger.Wallet balance(@AuthenticationPrincipal Jwt user, @PathVariable UUID id) {
    return ledger.balance(user.getSubject(), id);
  }

  @PostMapping("/test/fundings")
  Ledger.Operation fund(
      @AuthenticationPrincipal Jwt user,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Posting body,
      jakarta.servlet.http.HttpServletRequest req) {
    if (!funding) throw new LedgerException(404, "NOT_FOUND");
    return ledger.post(
        user.getSubject(),
        key,
        "FUNDING",
        body.sourceWalletId(),
        body.destinationWalletId(),
        body.amount(),
        body.currency(),
        (String) req.getAttribute("requestId"));
  }
}
