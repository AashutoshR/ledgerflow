package dev.ledgerflow.workspace;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace")
class ProposalController {
  private final Proposals proposals;

  ProposalController(Proposals proposals) {
    this.proposals = proposals;
  }

  @GetMapping("/proposals")
  List<Map<String, Object>> own(@AuthenticationPrincipal Jwt jwt) {
    return proposals.list(jwt, false);
  }

  @GetMapping("/portal")
  List<Map<String, Object>> portal(@AuthenticationPrincipal Jwt jwt) {
    return proposals.list(jwt, true);
  }

  @PostMapping("/proposals")
  Map<String, Object> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Proposals.Draft body) {
    return proposals.create(jwt, body);
  }

  @GetMapping("/proposals/{id}")
  Map<String, Object> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return proposals.detail(jwt, id);
  }

  @PostMapping("/proposals/{id}/publish")
  Map<String, Object> publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return proposals.publish(jwt, id);
  }

  @PostMapping("/proposals/{id}/decision")
  Map<String, Object> decide(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Proposals.Decision body) {
    return proposals.decide(jwt, id, body);
  }

  @PostMapping("/proposals/{id}/comments")
  void comment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Proposals.Comment body) {
    proposals.comment(jwt, id, body);
  }

  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  org.springframework.http.ResponseEntity<Map<String, String>> problem(
      org.springframework.web.server.ResponseStatusException e) {
    return org.springframework.http.ResponseEntity.status(e.getStatusCode())
        .body(Map.of("code", Objects.requireNonNullElse(e.getReason(), "REQUEST_FAILED")));
  }

  @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
  org.springframework.http.ResponseEntity<Map<String, String>> invalid() {
    return org.springframework.http.ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_INPUT"));
  }
}
