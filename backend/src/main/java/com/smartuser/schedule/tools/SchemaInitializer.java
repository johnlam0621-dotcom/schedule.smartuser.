package com.smartuser.schedule.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库结构初始化工具。
 *
 * 功能作用：
 * 1. 从 SCHEMA_FILE 指定的 SQL 文件读取建表/初始化语句，并按分号拆分后依次执行。
 * 2. 支持通过 MYSQL_INIT_URL、MYSQL_USERNAME、MYSQL_PASSWORD 环境变量连接目标 MySQL。
 * 3. 该工具用于本地或部署前初始化数据库，不参与 Spring Boot Web 应用运行时请求处理。
 */
public class SchemaInitializer {
  public static void main(String[] args) throws Exception {
    // 初始化连接参数和 schema 文件路径，未设置环境变量时使用本地约定默认值。
    String url = env("MYSQL_INIT_URL",
        "jdbc:mysql://127.0.0.1:3306/schedule_smartuser?useUnicode=true&characterEncoding=UTF-8&useAffectedRows=true&useTimezone=true&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true");
    String username = env("MYSQL_USERNAME", "root");
    String password = env("MYSQL_PASSWORD", "");
    String schemaFile = env("SCHEMA_FILE", "../database/schema.sql");

    Class.forName("com.mysql.cj.jdbc.Driver");
    String sql = new String(Files.readAllBytes(Paths.get(schemaFile)), StandardCharsets.UTF_8);
    List<String> statements = splitStatements(sql);

    int executed = 0;
    try (Connection connection = DriverManager.getConnection(url, username, password);
         Statement statement = connection.createStatement()) {
      for (String item : statements) {
        String trimmed = item.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        statement.execute(trimmed);
        executed++;
      }
    }
    System.out.println("Schema initialized. SQL statements executed: " + executed);
  }

  private static String env(String key, String fallback) {
    // 环境变量存在且非空时优先使用，便于不同环境复用同一初始化工具。
    String value = System.getenv(key);
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }

  private static List<String> splitStatements(String sql) {
    // 简单 SQL 拆分器：忽略单/双引号中的分号，并跳过 -- 单行注释。
    List<String> statements = new ArrayList<String>();
    StringBuilder current = new StringBuilder();
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;

    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

      if (!inSingleQuote && !inDoubleQuote && c == '-' && next == '-') {
        while (i < sql.length() && sql.charAt(i) != '\n') {
          i++;
        }
        current.append('\n');
        continue;
      }

      if (c == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
      } else if (c == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
      }

      if (c == ';' && !inSingleQuote && !inDoubleQuote) {
        statements.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }

    if (current.length() > 0) {
      statements.add(current.toString());
    }
    return statements;
  }
}
