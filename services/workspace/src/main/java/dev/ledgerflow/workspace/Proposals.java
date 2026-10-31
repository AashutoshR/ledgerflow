package dev.ledgerflow.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Proposals {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Workspace workspace;

  public Proposals(JdbcTemplate db, ObjectMapper json, Workspace workspace) {
    this.db = db;
    this.json = json;
    this.workspace = workspace;
  }

  public record Line(
      @NotBlank @Size(max = 160) String title,
      @NotNull @Pattern(regexp = "(0|[1-9][0-9]{0,8})\\.[0-9]{2}") String amount) {}

  public record Draft(
      @NotNull UUID id,
      @NotNull UUID clientId,
      @NotBlank @Size(max = 100) String title,
      @NotBlank @Size(max = 1000) String brief,
      @NotNull LocalDate dueDate,
      @NotEmpty @Size(max = 20) List<@NotNull @Valid Line> lines) {}

  public record Decision(@NotNull @Pattern(regexp = "ACCEPTED|DECLINED") String status) {}

  public record Comment(@NotNull UUID id, @NotBlank @Size(max = 1000) String body) {}

  private String email(Jwt jwt) {
    return Boolean.TRUE.equals(jwt.getClaim("email_verified"))
        ? Objects.toString(jwt.getClaimAsString("email"), "").strip().toLowerCase(Locale.ROOT)
        : "";
  }

  private ResponseStatusException missing() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND");
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "PROPOSAL_STATE_CONFLICT");
  }

  private Map<String, Object> row(UUID id) {
    var rows = db.queryForList("select * from proposals where id=? for update", id);
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }

  private boolean recipient(Jwt jwt, Map<String, Object> row) {
    return !email(jwt).isEmpty()
        && email(jwt).equals(row.get("recipient_email"))
        && !jwt.getSubject().equals(row.get("owner_id"))
        && !"DRAFT".equals(row.get("status"));
  }

  private void access(Jwt jwt, Map<String, Object> row) {
    if (!jwt.getSubject().equals(row.get("owner_id")) && !recipient(jwt, row)) throw missing();
  }

  private String encode(Draft draft) {
    try {
      return json.writeValueAsString(draft);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Draft decode(Map<String, Object> row) {
    try {
      return json.readValue(row.get("payload").toString(), Draft.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Transactional
  public Map<String, Object> create(Jwt jwt, Draft draft) {
    var clients =
        db.queryForList(
            "select email from clients where id=? and owner_id=?",
            draft.clientId(),
            jwt.getSubject());
    if (clients.isEmpty()) throw missing();
    // Decimal strings are converted exactly; there is no financial posting here.
    long total = 0;
    for (var line : draft.lines()) {
      long minor = new BigDecimal(line.amount()).movePointRight(2).longValueExact();
      if (minor <= 0)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "POSITIVE_LINE_AMOUNT_REQUIRED");
      total = Math.addExact(total, minor);
    }
    String payload = encode(draft);
    int n =
        db.update(
            "insert into proposals(id,owner_id,client_id,recipient_email,payload) values(?,?,?,?,?::jsonb) on conflict(id) do nothing",
            draft.id(),
            jwt.getSubject(),
            draft.clientId(),
            clients.getFirst().get("email").toString().strip().toLowerCase(Locale.ROOT),
            payload);
    if (n == 0
        && db.queryForList(
                "select id from proposals where id=? and owner_id=? and payload=?::jsonb",
                draft.id(),
                jwt.getSubject(),
                payload)
            .isEmpty()) throw conflict();
    if (n == 1) log(jwt.getSubject(), "Proposal drafted: " + draft.title());
    return detail(jwt, draft.id());
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> list(Jwt jwt, boolean portal) {
    var rows =
        portal
            ? db.queryForList(
                "select * from proposals where recipient_email=? and owner_id<>? and status<>'DRAFT' order by created_at desc,id",
                email(jwt),
                jwt.getSubject())
            : db.queryForList(
                "select * from proposals where owner_id=? order by created_at desc,id",
                jwt.getSubject());
    return rows.stream().map(this::view).toList();
  }

  @Transactional
  public Map<String, Object> detail(Jwt jwt, UUID id) {
    var row = row(id);
    access(jwt, row);
    return view(row);
  }

  @Transactional
  public Map<String, Object> publish(Jwt jwt, UUID id) {
    var row = row(id);
    if (!jwt.getSubject().equals(row.get("owner_id"))) throw missing();
    if ("DRAFT".equals(row.get("status"))) {
      db.update("update proposals set status='SENT' where id=?", id);
      log(jwt.getSubject(), "Proposal published to client portal: " + decode(row).title());
    } else if (!"SENT".equals(row.get("status"))) throw conflict();
    return detail(jwt, id);
  }

  @Transactional
  public Map<String, Object> decide(Jwt jwt, UUID id, Decision decision) {
    var row = row(id);
    if (!recipient(jwt, row)) throw missing();
    if (decision.status().equals(row.get("status"))) return view(row);
    if (!"SENT".equals(row.get("status"))) throw conflict();
    var draft = decode(row);
    UUID project = null;
    if ("ACCEPTED".equals(decision.status())) {
      project = UUID.randomUUID();
      String owner = row.get("owner_id").toString();
      workspace.createProject(
          owner,
          new Workspace.Project(
              project, draft.clientId(), draft.title(), draft.brief(), draft.dueDate()));
      for (var line : draft.lines()) {
        UUID taskId = UUID.randomUUID();
        workspace.createTask(owner, new Workspace.Task(taskId, project, line.title()));
        db.update("insert into proposal_deliverables(proposal_id,task_id) values(?,?)", id, taskId);
      }
    }
    db.update(
        "update proposals set status=?,project_id=?,decided_by=?,decided_at=now() where id=?",
        decision.status(),
        project,
        jwt.getSubject(),
        id);
    log(
        row.get("owner_id").toString(),
        "Proposal " + decision.status().toLowerCase() + ": " + draft.title());
    return detail(jwt, id);
  }

  @Transactional
  public void comment(Jwt jwt, UUID id, Comment comment) {
    var row = row(id);
    access(jwt, row);
    if ("DRAFT".equals(row.get("status"))) throw conflict();
    String role = jwt.getSubject().equals(row.get("owner_id")) ? "STUDIO" : "CLIENT";
    int n =
        db.update(
            "insert into proposal_comments(id,proposal_id,author_id,author_role,body) values(?,?,?,?,?) on conflict(id) do nothing",
            comment.id(),
            id,
            jwt.getSubject(),
            role,
            comment.body().strip());
    if (n == 0
        && db.queryForList(
                "select id from proposal_comments where id=? and proposal_id=? and author_id=? and body=?",
                comment.id(),
                id,
                jwt.getSubject(),
                comment.body().strip())
            .isEmpty()) throw conflict();
    if (n == 1)
      log(row.get("owner_id").toString(), "Proposal feedback added: " + decode(row).title());
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var draft = decode(row);
    var result = new LinkedHashMap<String, Object>();
    result.put("id", draft.id());
    result.put("clientId", draft.clientId());
    result.put("title", draft.title());
    result.put("brief", draft.brief());
    result.put("dueDate", draft.dueDate());
    result.put("lines", draft.lines());
    result.put("status", row.get("status"));
    result.put("projectId", row.get("project_id"));
    result.put("recipientEmail", row.get("recipient_email"));
    result.put("createdAt", row.get("created_at"));
    result.put(
        "company",
        db.queryForObject(
            "select company from clients where id=? and owner_id=?",
            String.class,
            draft.clientId(),
            row.get("owner_id")));
    result.put(
        "comments",
        db.queryForList(
            "select id,author_role as \"authorRole\",body,created_at as \"createdAt\" from proposal_comments where proposal_id=? order by created_at,id",
            draft.id()));
    result.put(
        "tasks",
        row.get("project_id") == null
            ? List.of()
            : db.queryForList(
                "select t.id,t.title,t.status from tasks t join proposal_deliverables d on d.task_id=t.id where d.proposal_id=? and t.owner_id=? order by t.created_at,t.id",
                draft.id(),
                row.get("owner_id")));
    return result;
  }

  private void log(String owner, String message) {
    db.update(
        "insert into activity(id,owner_id,message) values(?,?,?)",
        UUID.randomUUID(),
        owner,
        message);
  }
}
