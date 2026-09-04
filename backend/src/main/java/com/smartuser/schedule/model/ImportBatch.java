package com.smartuser.schedule.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 导入批次实体。
 *
 * 功能作用：
 * 1. 映射 schedule_import_batch 表，记录每次上传文件的导入统计。
 * 2. batchId 会写入每条 InspectionRecord，便于按来源文件追踪数据。
 */
@TableName("schedule_import_batch")
public class ImportBatch {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String fileName;
  private String fileType;
  private Integer totalRows;
  private Integer successRows;
  private Integer failedRows;
  private String createdBy;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getFileName() { return fileName; }
  public void setFileName(String fileName) { this.fileName = fileName; }
  public String getFileType() { return fileType; }
  public void setFileType(String fileType) { this.fileType = fileType; }
  public Integer getTotalRows() { return totalRows; }
  public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }
  public Integer getSuccessRows() { return successRows; }
  public void setSuccessRows(Integer successRows) { this.successRows = successRows; }
  public Integer getFailedRows() { return failedRows; }
  public void setFailedRows(Integer failedRows) { this.failedRows = failedRows; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
