import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class ExportUsers {
  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value;
  }

  private static String text(ResultSet rows, String column) throws Exception {
    String value = rows.getString(column);
    return value == null ? "" : value;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Output SQL path is required");
    Class.forName("com.mysql.cj.jdbc.Driver");
    String query = "SELECT id, HEX(username) username_hex, HEX(password_hash) password_hex, "
        + "HEX(real_name) real_name_hex, HEX(role_code) role_hex, status, "
        + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') created_at, "
        + "DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') updated_at "
        + "FROM sys_user WHERE username <> 'admin' ORDER BY id";
    int count = 0;
    try (Connection connection = DriverManager.getConnection(
            required("MYSQL_URL"), required("MYSQL_USERNAME"), required("MYSQL_PASSWORD"));
         PreparedStatement statement = connection.prepareStatement(query);
         ResultSet rows = statement.executeQuery();
         BufferedWriter writer = Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8)) {
      writer.write("SET NAMES utf8mb4;\nSTART TRANSACTION;\nDELETE FROM sys_auth_session;\n");
      while (rows.next()) {
        writer.write("INSERT INTO sys_user(id,username,password_hash,real_name,role_code,status,created_at,updated_at) VALUES(");
        writer.write(rows.getLong("id") + ",");
        writer.write("CONVERT(UNHEX('" + text(rows, "username_hex") + "') USING utf8mb4),");
        writer.write("CONVERT(UNHEX('" + text(rows, "password_hex") + "') USING utf8mb4),");
        writer.write("CONVERT(UNHEX('" + text(rows, "real_name_hex") + "') USING utf8mb4),");
        writer.write("CONVERT(UNHEX('" + text(rows, "role_hex") + "') USING utf8mb4),");
        writer.write(rows.getInt("status") + ",'");
        writer.write(text(rows, "created_at") + "','" + text(rows, "updated_at") + "') ");
        writer.write("ON DUPLICATE KEY UPDATE username=VALUES(username),password_hash=VALUES(password_hash),");
        writer.write("real_name=VALUES(real_name),role_code=VALUES(role_code),status=VALUES(status),updated_at=VALUES(updated_at);\n");
        count++;
      }
      writer.write("COMMIT;\n");
    }
    System.out.println("Exported non-admin users: " + count);
  }
}
