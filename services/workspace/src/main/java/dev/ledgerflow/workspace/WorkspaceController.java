package dev.ledgerflow.workspace;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace")
class WorkspaceController {
  private final Workspace workspace;

  WorkspaceController(Workspace workspace) {
    this.workspace = workspace;
  }

  @GetMapping
  Map<String, Object> snapshot(@AuthenticationPrincipal Jwt jwt) {
    return workspace.snapshot(jwt.getSubject());
  }

  @PostMapping("/clients")
  Workspace.Client client(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Workspace.Client body) {
    return workspace.createClient(jwt.getSubject(), body);
  }

  @PostMapping("/projects")
  Workspace.Project project(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Workspace.Project body) {
    return workspace.createProject(jwt.getSubject(), body);
  }

  @PostMapping("/tasks")
  Workspace.Task task(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Workspace.Task body) {
    return workspace.createTask(jwt.getSubject(), body);
  }

  @PatchMapping("/tasks/{id}")
  void move(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody Workspace.Move body) {
    workspace.move(jwt.getSubject(), id, body);
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
