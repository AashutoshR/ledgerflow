package dev.ledgerflow.walletledger;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/transfers")
class InternalTransferController {
  private final InternalTransfers transfers;

  InternalTransferController(InternalTransfers transfers) {
    this.transfers = transfers;
  }

  @PutMapping("/{reference}")
  InternalTransfers.Decision post(
      @PathVariable UUID reference,
      @RequestBody InternalTransfers.Request request,
      HttpServletRequest http) {
    return transfers.post(reference, request, (String) http.getAttribute("requestId"));
  }

  @GetMapping("/{reference}")
  InternalTransfers.Decision lookup(@PathVariable UUID reference) {
    return transfers.lookup(reference);
  }
}
