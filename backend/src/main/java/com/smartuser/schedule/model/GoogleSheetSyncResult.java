package com.smartuser.schedule.model;

/**
 * Google Sheet 同步结果对象。
 *
 * 功能作用：
 * 1. 表示同步状态：success 成功、failed 失败、skipped 跳过。
 * 2. rows 表示同步行数，message 表示前端可展示的状态说明。
 */
public class GoogleSheetSyncResult {
  private String status;
  private int rows;
  private String message;

  public static GoogleSheetSyncResult skipped(String message) {
    return of("skipped", 0, message);
  }

  public static GoogleSheetSyncResult success(int rows, String message) {
    return of("success", rows, message);
  }

  public static GoogleSheetSyncResult failed(String message) {
    return of("failed", 0, message);
  }

  private static GoogleSheetSyncResult of(String status, int rows, String message) {
    GoogleSheetSyncResult result = new GoogleSheetSyncResult();
    result.setStatus(status);
    result.setRows(rows);
    result.setMessage(message);
    return result;
  }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public int getRows() { return rows; }
  public void setRows(int rows) { this.rows = rows; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
}
