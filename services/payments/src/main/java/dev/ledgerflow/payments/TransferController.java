package dev.ledgerflow.payments;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController {
  private final Transfers transfers;

  TransferController(Transfers transfers) {
    this.transfers = transfers;
  }

  record Result(
      UUID id,
      String status,
      String amount,
      String currency,
      UUID ledgerOperationId,
      String errorCode) {}

  private Result result(Transfers.Transfer t) {
    return new Result(
        t.id(), t.status(), t.amount(), t.currency(), t.ledgerOperationId(), t.errorCode());
  }

  @PostMapping
  ResponseEntity<Result> create(
      @AuthenticationPrincipal Jwt user,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Transfers.Request body,
      HttpServletRequest request) {
    var transfer =
        transfers.create(user.getSubject(), key, body, (String) request.getAttribute("requestId"));
    boolean pending = transfer.status().equals("CREATED") || transfer.status().equals("PROCESSING");
    return ResponseEntity.status(pending ? 202 : 200)
        .location(URI.create("/api/v1/transfers/" + transfer.id()))
        .body(result(transfer));
  }

  @GetMapping("/{id}")
  Result get(@AuthenticationPrincipal Jwt user, @PathVariable UUID id) {
    return result(transfers.get(user.getSubject(), id));
  }
}
