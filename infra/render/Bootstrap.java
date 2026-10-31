import java.sql.DriverManager;

public class Bootstrap {
  public static void main(String[] args) throws Exception {
    String url = "jdbc:postgresql://" + System.getenv("PGHOST") + ":"
        + System.getenv().getOrDefault("PGPORT", "5432") + "/" + System.getenv("PGDATABASE");
    try (var db = DriverManager.getConnection(url, System.getenv("PGUSER"), System.getenv("PGPASSWORD"));
        var statement = db.createStatement()) {
      statement.execute("CREATE SCHEMA IF NOT EXISTS keycloak");
    }
  }
}
