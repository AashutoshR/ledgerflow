package dev.ledgerflow.workspace;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Workspace {
  private final JdbcTemplate db;

  public Workspace(JdbcTemplate db) {
    this.db = db;
  }

  public record Client(
      @NotNull UUID id,
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(max = 100) String company) {}

  public record Project(
      @NotNull UUID id,
      UUID clientId,
      @NotBlank @Size(max = 100) String name,
      @NotNull @Size(max = 1000) String description,
      @NotNull LocalDate dueDate) {}

  public record Task(
      @NotNull UUID id, @NotNull UUID projectId, @NotBlank @Size(max = 160) String title) {}

  public record Move(
      @NotNull @Pattern(regexp = "TODO|DOING|DONE") String status, @Min(0) int version) {}

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Map<String, Object> snapshot(String owner) {
    return Map.of(
        "clients",
            db.queryForList(
                "select id,name,email,company,created_at as \"createdAt\" from clients where owner_id=? order by created_at desc,id",
                owner),
        "projects",
            db.queryForList(
                "select id,client_id as \"clientId\",name,description,due_date as \"dueDate\",created_at as \"createdAt\" from projects where owner_id=? order by created_at desc,id",
                owner),
        "tasks",
            db.queryForList(
                "select id,project_id as \"projectId\",title,status,version from tasks where owner_id=? order by created_at,id",
                owner),
        "activity",
            db.queryForList(
                "select id,message,created_at as \"createdAt\" from activity where owner_id=? order by created_at desc,id limit 40",
                owner));
  }

  @Transactional
  public Client createClient(String owner, Client r) {
    int n =
        db.update(
            "insert into clients(id,owner_id,name,email,company) values(?,?,?,?,?) on conflict(id) do nothing",
            r.id(),
            owner,
            r.name().strip(),
            r.email().strip(),
            r.company().strip());
    if (n == 0) {
      var rows =
          db.queryForList(
              "select id from clients where id=? and owner_id=? and name=? and email=? and company=?",
              r.id(),
              owner,
              r.name().strip(),
              r.email().strip(),
              r.company().strip());
      if (rows.isEmpty()) conflict();
    } else log(owner, "Client added: " + r.name().strip());
    return r;
  }

  @Transactional
  public Project createProject(String owner, Project r) {
    if (r.clientId() != null) owned("clients", r.clientId(), owner);
    int n =
        db.update(
            "insert into projects(id,owner_id,client_id,name,description,due_date) values(?,?,?,?,?,?) on conflict(id) do nothing",
            r.id(),
            owner,
            r.clientId(),
            r.name().strip(),
            r.description().strip(),
            r.dueDate());
    if (n == 0) {
      var rows =
          db.queryForList(
              "select id from projects where id=? and owner_id=? and client_id is not distinct from ? and name=? and description=? and due_date=?",
              r.id(),
              owner,
              r.clientId(),
              r.name().strip(),
              r.description().strip(),
              r.dueDate());
      if (rows.isEmpty()) conflict();
    } else log(owner, "Project opened: " + r.name().strip());
    return r;
  }

  @Transactional
  public Task createTask(String owner, Task r) {
    owned("projects", r.projectId(), owner);
    int n =
        db.update(
            "insert into tasks(id,owner_id,project_id,title) values(?,?,?,?) on conflict(id) do nothing",
            r.id(),
            owner,
            r.projectId(),
            r.title().strip());
    if (n == 0) {
      if (db.queryForList(
              "select id from tasks where id=? and owner_id=? and project_id=? and title=?",
              r.id(),
              owner,
              r.projectId(),
              r.title().strip())
          .isEmpty()) conflict();
    } else log(owner, "Task added: " + r.title().strip());
    return r;
  }

  @Transactional
  public void move(String owner, UUID id, Move r) {
    owned("tasks", id, owner);
    int n =
        db.update(
            "update tasks set status=?,version=version+1 where id=? and owner_id=? and version=?",
            r.status(),
            id,
            owner,
            r.version());
    if (n == 0) conflict();
    log(
        owner,
        "Task moved to "
            + r.status().toLowerCase()
            + ": "
            + db.queryForObject(
                "select title from tasks where id=? and owner_id=?", String.class, id, owner));
  }

  private void owned(String table, UUID id, String owner) {
    if (db.queryForObject(
            "select count(*) from " + table + " where id=? and owner_id=?",
            Integer.class,
            id,
            owner)
        == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RECORD_NOT_FOUND");
  }

  private void conflict() {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "RECORD_CONFLICT");
  }

  private void log(String owner, String message) {
    db.update(
        "insert into activity(id,owner_id,message) values(?,?,?)",
        UUID.randomUUID(),
        owner,
        message);
  }
}
