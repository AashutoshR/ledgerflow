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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProposalsIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired Proposals proposals;
  @Autowired Workspace workspace;
  @Autowired MockMvc mvc;

  Jwt token(String sub, String email, boolean verified) {
    return Jwt.withTokenValue("test")
        .header("alg", "none")
        .subject(sub)
        .claim("email", email)
        .claim("email_verified", verified)
        .build();
  }

  record Setup(Jwt owner, Jwt client, Proposals.Draft draft) {}

  Setup setup() {
    String owner = UUID.randomUUID().toString(), email = UUID.randomUUID() + "@example.test";
    var c = new Workspace.Client(UUID.randomUUID(), "Alex", email, "Atelier");
    workspace.createClient(owner, c);
    var draft =
        new Proposals.Draft(
            UUID.randomUUID(),
            c.id(),
            "Launch",
            "A carefully scoped launch",
            LocalDate.of(2026, 12, 1),
            List.of(
                new Proposals.Line("Discovery", "250.25"),
                new Proposals.Line("Delivery", "500.00")));
    return new Setup(
        token(owner, "studio@example.test", true),
        token(UUID.randomUUID().toString(), email, true),
        draft);
  }

  @Test
  void draftsArePrivateAndCreationRetriesDoNotDuplicate() {
    var s = setup();
    proposals.create(s.owner(), s.draft());
    proposals.create(s.owner(), s.draft());
    assertThat(proposals.list(s.owner(), false)).hasSize(1);
    assertThat(proposals.list(s.client(), true)).isEmpty();
    assertThatThrownBy(() -> proposals.detail(s.client(), s.draft().id()))
        .hasMessageContaining("PROPOSAL_NOT_FOUND");
    assertThatThrownBy(
            () ->
                proposals.create(
                    s.owner(),
                    new Proposals.Draft(
                        s.draft().id(),
                        s.draft().clientId(),
                        "Changed",
                        "Brief",
                        s.draft().dueDate(),
                        s.draft().lines())))
        .hasMessageContaining("PROPOSAL_STATE_CONFLICT");
  }

  @Test
  void publishedPortalRequiresVerifiedRecipientAndOwnerCannotAccept() {
    var s = setup();
    proposals.create(s.owner(), s.draft());
    proposals.publish(s.owner(), s.draft().id());
    assertThat(proposals.list(s.client(), true)).hasSize(1);
    var unverified = token(s.client().getSubject(), s.client().getClaimAsString("email"), false);
    assertThat(proposals.list(unverified, true)).isEmpty();
    for (var actor :
        List.of(s.owner(), unverified, token("stranger", "stranger@example.test", true))) {
      assertThatThrownBy(
              () -> proposals.decide(actor, s.draft().id(), new Proposals.Decision("ACCEPTED")))
          .hasMessageContaining("PROPOSAL_NOT_FOUND");
    }
  }

  @Test
  void concurrentAcceptanceCreatesOneProjectAndOneSetOfTasks() throws Exception {
    var s = setup();
    proposals.create(s.owner(), s.draft());
    proposals.publish(s.owner(), s.draft().id());
    try (var pool = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Callable<Map<String, Object>>>();
      for (int i = 0; i < 4; i++)
        calls.add(
            () -> proposals.decide(s.client(), s.draft().id(), new Proposals.Decision("ACCEPTED")));
      Set<Object> ids = new HashSet<>();
      for (var result : pool.invokeAll(calls)) ids.add(result.get().get("projectId"));
      assertThat(ids).hasSize(1);
    }
    var snapshot = workspace.snapshot(s.owner().getSubject());
    assertThat((List<?>) snapshot.get("projects")).hasSize(1);
    assertThat((List<?>) snapshot.get("tasks")).hasSize(2);
    assertThat((List<?>) proposals.detail(s.client(), s.draft().id()).get("tasks")).hasSize(2);
    UUID projectId = (UUID) proposals.detail(s.owner(), s.draft().id()).get("projectId");
    workspace.createTask(
        s.owner().getSubject(),
        new Workspace.Task(UUID.randomUUID(), projectId, "Internal studio planning"));
    assertThat((List<?>) proposals.detail(s.client(), s.draft().id()).get("tasks")).hasSize(2);
  }

  @Test
  void declineIsTerminalAndCommentsAreOwnedAndRetrySafe() {
    var s = setup();
    proposals.create(s.owner(), s.draft());
    proposals.publish(s.owner(), s.draft().id());
    var c = new Proposals.Comment(UUID.randomUUID(), "Could we discuss the scope?");
    proposals.comment(s.client(), s.draft().id(), c);
    proposals.comment(s.client(), s.draft().id(), c);
    assertThat((List<?>) proposals.detail(s.owner(), s.draft().id()).get("comments")).hasSize(1);
    assertThatThrownBy(
            () ->
                proposals.comment(token("stranger", "wrong@example.test", true), s.draft().id(), c))
        .hasMessageContaining("PROPOSAL_NOT_FOUND");
    proposals.decide(s.client(), s.draft().id(), new Proposals.Decision("DECLINED"));
    assertThatThrownBy(
            () -> proposals.decide(s.client(), s.draft().id(), new Proposals.Decision("ACCEPTED")))
        .hasMessageContaining("PROPOSAL_STATE_CONFLICT");
    assertThat((List<?>) workspace.snapshot(s.owner().getSubject()).get("projects")).isEmpty();
  }

  @Test
  void apiValidatesAmountsAndRejectsUnauthenticatedPortal() throws Exception {
    mvc.perform(get("/api/v1/workspace/portal")).andExpect(status().isUnauthorized());
    var s = setup();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var invalid =
        new Proposals.Draft(
            s.draft().id(),
            s.draft().clientId(),
            "Bad",
            "Brief",
            s.draft().dueDate(),
            List.of(new Proposals.Line("Negative", "-1.00")));
    mvc.perform(
            post("/api/v1/workspace/proposals")
                .with(jwt().jwt(j -> j.subject(s.owner().getSubject())))
                .contentType("application/json")
                .content(mapper.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest());
  }
}
