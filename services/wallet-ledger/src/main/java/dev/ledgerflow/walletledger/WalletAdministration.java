package dev.ledgerflow.walletledger;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class WalletAdministration {
  private final WalletQueries wallets;

  WalletAdministration(WalletQueries wallets) {
    this.wallets = wallets;
  }

  record FreezeRequest(Boolean frozen, String reason) {}

  @GetMapping("/wallets/{id}/transactions")
  WalletQueries.Page history(
      @AuthenticationPrincipal Jwt user,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return wallets.history(user.getSubject(), id, page, size);
  }

  @PutMapping("/admin/wallets/{id}/freeze")
  WalletQueries.FreezeState freeze(
      @AuthenticationPrincipal Jwt user,
      @PathVariable UUID id,
      @RequestBody FreezeRequest body,
      HttpServletRequest request) {
    if (body.frozen() == null) throw new LedgerException(400, "INVALID_FREEZE_STATE");
    return wallets.freeze(
        user.getSubject(),
        id,
        body.frozen(),
        body.reason(),
        (String) request.getAttribute("requestId"));
  }
}
