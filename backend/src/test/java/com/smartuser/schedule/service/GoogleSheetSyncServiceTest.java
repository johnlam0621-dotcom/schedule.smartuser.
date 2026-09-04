package com.smartuser.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartuser.schedule.config.GoogleSheetProperties;
import com.smartuser.schedule.model.GoogleSheetSyncResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleSheetSyncServiceTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void followsAppsScriptPostRedirectWithGet() throws Exception {
    // 模拟 Apps Script：POST /exec 返回 302，最终 JSON 必须使用 GET 读取。
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/exec", exchange -> {
      exchange.getResponseHeaders().add("Location", baseUrl() + "/result");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/result", exchange -> {
      if (!"GET".equals(exchange.getRequestMethod())) {
        send(exchange, 405, "method-not-allowed");
        return;
      }
      send(exchange, 200, "{\"ok\":true,\"sheetName\":\"Week 388\"}");
    });
    server.start();

    GoogleSheetProperties properties = new GoogleSheetProperties();
    properties.setEnabled(true);
    properties.setSpreadsheetId("test-sheet");
    properties.setSheetGid(388L);
    properties.setWebAppUrl(baseUrl() + "/exec");
    properties.setWebAppSecret("test-secret");
    GoogleSheetSyncService service = new GoogleSheetSyncService(properties, new ObjectMapper());

    GoogleSheetSyncResult result = service.checkStatus();

    assertThat(result.getStatus()).isEqualTo("success");
    assertThat(result.getMessage()).contains("Week 388");
  }

  @Test
  void neverAppendsImportedRowsToOperationalSheet() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/exec", exchange -> {
      requests.incrementAndGet();
      send(exchange, 500, "unexpected-request");
    });
    server.start();
    GoogleSheetProperties properties = new GoogleSheetProperties();
    properties.setEnabled(true);
    properties.setSpreadsheetId("test-sheet");
    properties.setSheetGid(388L);
    properties.setWebAppUrl(baseUrl() + "/exec");
    properties.setWebAppSecret("test-secret");
    GoogleSheetSyncService service = new GoogleSheetSyncService(properties, new ObjectMapper());

    GoogleSheetSyncResult result = service.syncImport(Collections.emptyList());

    assertThat(result.getStatus()).isEqualTo("skipped");
    assertThat(requests.get()).isZero();
  }

  @Test
  void neverWritesBookingChangesToOperationalSheet() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/exec", exchange -> {
      requests.incrementAndGet();
      send(exchange, 500, "unexpected-request");
    });
    server.start();
    GoogleSheetProperties properties = new GoogleSheetProperties();
    properties.setEnabled(true);
    properties.setSpreadsheetId("test-sheet");
    properties.setSheetGid(388L);
    properties.setWebAppUrl(baseUrl() + "/exec");
    properties.setWebAppSecret("test-secret");
    GoogleSheetSyncService service = new GoogleSheetSyncService(properties, new ObjectMapper());

    GoogleSheetSyncResult result = service.updateBookingRow(new com.smartuser.schedule.model.InspectionRecord());

    assertThat(result.getStatus()).isEqualTo("skipped");
    assertThat(requests.get()).isZero();
  }

  @Test
  void previewTreatsBlankDatedTimeRowAsImportableSpace() throws Exception {
    GoogleSheetProperties properties = new GoogleSheetProperties();
    GoogleSheetSyncService service = new GoogleSheetSyncService(properties, new ObjectMapper());
    List<Object> row = new ArrayList<Object>(Collections.nCopies(13, ""));
    row.set(11, "2-Sep");
    row.set(12, "10:00 AM");
    Method method = GoogleSheetSyncService.class.getDeclaredMethod("isImportableRow", List.class);
    method.setAccessible(true);

    assertThat((Boolean) method.invoke(service, row)).isTrue();
  }

  @Test
  void serviceAccountTakesPrecedenceWhenBothCredentialMethodsAreConfigured() throws Exception {
    AtomicInteger webAppRequests = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/exec", exchange -> {
      webAppRequests.incrementAndGet();
      send(exchange, 500, "unexpected-request");
    });
    server.start();

    GoogleSheetProperties properties = new GoogleSheetProperties();
    properties.setEnabled(true);
    properties.setSpreadsheetId("test-sheet");
    properties.setServiceAccountJson("{}");
    properties.setWebAppUrl(baseUrl() + "/exec");
    properties.setWebAppSecret("stale-secret");
    GoogleSheetSyncService service = new GoogleSheetSyncService(properties, new ObjectMapper());

    assertThatThrownBy(service::listImportSheets)
        .hasMessageContaining("service account");
    assertThat(webAppRequests.get()).isZero();
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
