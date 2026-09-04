package com.smartuser.schedule.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件导入结果对象。
 *
 * 功能作用：
 * 1. 返回前端导入批次号、扫描行数、成功行数、失败行数和逐行错误信息。
 * 2. 同时包含 Google Sheet 同步状态，前端可分别提示“导入成功”和“同步成功/失败”。
 */
public class ImportResult {
  private Long batchId;
  private String fileName;
  private int totalRows;
  private int successRows;
  private int failedRows;
  private List<String> errors = new ArrayList<String>();
  private String googleSheetStatus;
  private int googleSheetRows;
  private String googleSheetMessage;

  public Long getBatchId() { return batchId; }
  public void setBatchId(Long batchId) { this.batchId = batchId; }
  public String getFileName() { return fileName; }
  public void setFileName(String fileName) { this.fileName = fileName; }
  public int getTotalRows() { return totalRows; }
  public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
  public int getSuccessRows() { return successRows; }
  public void setSuccessRows(int successRows) { this.successRows = successRows; }
  public int getFailedRows() { return failedRows; }
  public void setFailedRows(int failedRows) { this.failedRows = failedRows; }
  public List<String> getErrors() { return errors; }
  public void setErrors(List<String> errors) { this.errors = errors; }
  public String getGoogleSheetStatus() { return googleSheetStatus; }
  public void setGoogleSheetStatus(String googleSheetStatus) { this.googleSheetStatus = googleSheetStatus; }
  public int getGoogleSheetRows() { return googleSheetRows; }
  public void setGoogleSheetRows(int googleSheetRows) { this.googleSheetRows = googleSheetRows; }
  public String getGoogleSheetMessage() { return googleSheetMessage; }
  public void setGoogleSheetMessage(String googleSheetMessage) { this.googleSheetMessage = googleSheetMessage; }
}
