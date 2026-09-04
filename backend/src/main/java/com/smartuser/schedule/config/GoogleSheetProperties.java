package com.smartuser.schedule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Google Sheet 同步配置属性。
 *
 * 功能作用：
 * 1. 读取 application.yml 和环境变量中的 integration.google-sheet 配置。
 * 2. 支持服务账号和 Apps Script Web App 两种同步凭据。
 * 3. 统一管理 spreadsheetId、sheetGid、批量追加大小、连接超时、读取超时等参数。
 */
@Component
@ConfigurationProperties(prefix = "integration.google-sheet")
public class GoogleSheetProperties {
  private boolean enabled;
  private String spreadsheetId;
  private Long sheetGid;
  private String sheetName;
  private String serviceAccountFile;
  private String serviceAccountJson;
  private String serviceAccountBase64;
  private String webAppUrl;
  private String webAppSecret;
  private int batchSize = 500;
  private int connectTimeoutMillis = 10000;
  private int readTimeoutMillis = 60000;
  private boolean autoImportOnLogin = true;
  private int autoImportMaxWeeks = 8;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public String getSpreadsheetId() { return spreadsheetId; }
  public void setSpreadsheetId(String spreadsheetId) { this.spreadsheetId = spreadsheetId; }
  public Long getSheetGid() { return sheetGid; }
  public void setSheetGid(Long sheetGid) { this.sheetGid = sheetGid; }
  public String getSheetName() { return sheetName; }
  public void setSheetName(String sheetName) { this.sheetName = sheetName; }
  public String getServiceAccountFile() { return serviceAccountFile; }
  public void setServiceAccountFile(String serviceAccountFile) { this.serviceAccountFile = serviceAccountFile; }
  public String getServiceAccountJson() { return serviceAccountJson; }
  public void setServiceAccountJson(String serviceAccountJson) { this.serviceAccountJson = serviceAccountJson; }
  public String getServiceAccountBase64() { return serviceAccountBase64; }
  public void setServiceAccountBase64(String serviceAccountBase64) { this.serviceAccountBase64 = serviceAccountBase64; }
  public String getWebAppUrl() { return webAppUrl; }
  public void setWebAppUrl(String webAppUrl) { this.webAppUrl = webAppUrl; }
  public String getWebAppSecret() { return webAppSecret; }
  public void setWebAppSecret(String webAppSecret) { this.webAppSecret = webAppSecret; }
  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
  public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
  public void setConnectTimeoutMillis(int connectTimeoutMillis) { this.connectTimeoutMillis = connectTimeoutMillis; }
  public int getReadTimeoutMillis() { return readTimeoutMillis; }
  public void setReadTimeoutMillis(int readTimeoutMillis) { this.readTimeoutMillis = readTimeoutMillis; }
  public boolean isAutoImportOnLogin() { return autoImportOnLogin; }
  public void setAutoImportOnLogin(boolean autoImportOnLogin) { this.autoImportOnLogin = autoImportOnLogin; }
  public int getAutoImportMaxWeeks() { return autoImportMaxWeeks; }
  public void setAutoImportMaxWeeks(int autoImportMaxWeeks) { this.autoImportMaxWeeks = autoImportMaxWeeks; }
}
