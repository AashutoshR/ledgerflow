package dev.ledgerflow.workspace;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkspaceIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired Workspace workspace;
  @Autowired MockMvc mvc;

  @Test
  void isolatedOwnershipAndSafeCreationRetries() {
    String owner = UUID.randomUUID().toString();
    var c = new Workspace.Client(UUID.randomUUID(), "Ella", "ella@example.test", "North Studio");
    workspace.createClient(owner, c);
    workspace.createClient(owner, c);
    assertThat((List<?>) workspace.snapshot(owner).get("clients")).hasSize(1);
    assertThat((List<?>) workspace.snapshot("stranger").get("clients")).isEmpty();
    assertThatThrownBy(() -> workspace.createClient("stranger", c))
        .hasMessageContaining("RECORD_CONFLICT");
    assertThatThrownBy(
            () ->
                workspace.createProject(
                    "stranger",
                    new Workspace.Project(
                        UUID.randomUUID(), c.id(), "Private", "", LocalDate.now())))
        .hasMessageContaining("RECORD_NOT_FOUND");
    assertThat((List<?>) workspace.snapshot(owner).get("activity")).hasSize(1);
  }

  @Test
  void concurrentTaskMovesRejectLostUpdates() throws Exception {
    String owner = UUID.randomUUID().toString();
    var p =
        new Workspace.Project(
            UUID.randomUUID(), null, "Launch", "A launch", LocalDate.now().plusDays(7));
    workspace.createProject(owner, p);
    var t = new Workspace.Task(UUID.randomUUID(), p.id(), "Prepare proposal");
    workspace.createTask(owner, t);
    assertThatThrownBy(() -> workspace.move("stranger", t.id(), new Workspace.Move("DONE", 0)))
        .hasMessageContaining("RECORD_NOT_FOUND");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results =
          pool.invokeAll(
              List.<Callable<Boolean>>of(() -> move(owner, t.id()), () -> move(owner, t.id())));
      int success = 0;
      for (var result : results) if (result.get()) success++;
      assertThat(success).isEqualTo(1);
    }
    var tasks = (List<Map<String, Object>>) workspace.snapshot(owner).get("tasks");
    assertThat(tasks.getFirst().get("version")).isEqualTo(1);
    assertThat(tasks.getFirst().get("status")).isEqualTo("DONE");
  }

  @Test
  void apiRoundTripPreservesDatesAndPreventsCrossOwnerTaskMutation() throws Exception {
    String owner = UUID.randomUUID().toString();
    UUID id = UUID.randomUUID(), taskId = UUID.randomUUID();
    String body =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "id", id, "name", "Launch", "description", "Brief", "dueDate", "2026-12-01"));
    mvc.perform(
            post("/api/v1/workspace/projects")
                .with(jwt().jwt(j -> j.subject(owner)))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/workspace").with(jwt().jwt(j -> j.subject(owner))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projects[0].dueDate").value("2026-12-01"));
    workspace.createTask(owner, new Workspace.Task(taskId, id, "Review launch"));
    mvc.perform(
            patch("/api/v1/workspace/tasks/" + taskId)
                .with(jwt().jwt(j -> j.subject("stranger")))
                .contentType("application/json")
                .content(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(Map.of("status", "DONE", "version", 0))))
        .andExpect(status().isNotFound());
    mvc.perform(
            patch("/api/v1/workspace/tasks/" + taskId)
                .with(jwt().jwt(j -> j.subject(owner)))
                .contentType("application/json")
                .content(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(Map.of("status", "DONE", "version", 0))))
        .andExpect(status().isOk());
  }

  private boolean move(String owner, UUID id) {
    try {
      workspace.move(owner, id, new Workspace.Move("DONE", 0));
      return true;
    } catch (org.springframework.web.server.ResponseStatusException e) {
      assertThat(e.getStatusCode().value()).isEqualTo(409);
      return false;
    }
  }

  @Test
  void apiRequiresAuthenticationAndValidInput() throws Exception {
    mvc.perform(get("/api/v1/workspace")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/workspace/clients")
                .with(jwt())
                .contentType("application/json")
                .content(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                            Map.of(
                                "id",
                                UUID.randomUUID(),
                                "name",
                                " ",
                                "email",
                                "broken",
                                "company",
                                "North"))))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace").with(jwt().jwt(j -> j.subject("new-user"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projects").isEmpty());
  }
}
