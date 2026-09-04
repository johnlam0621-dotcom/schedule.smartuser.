package com.smartuser.schedule.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 巡检记录实体。
 *
 * 功能作用：
 * 1. 映射 schedule_inspection 表，保存 CSV/Excel 导入后的每一条客户巡检数据。
 * 2. Inspection Import 列表、Routes map 列表、详情、编辑、删除、导出都基于该实体。
 * 3. rawJson 保留导入行的标准化原始字段，便于排查模板字段映射问题。
 */
@TableName("schedule_inspection")
public class InspectionRecord {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long importBatchId;
  @TableField("row_num")
  private Integer rowNumber;
  @TableField("day_of_week")
  private String dayName;
  private String status;
  private String macId;
  private String sales;
  private String firstName;
  private String lastName;
  private String customerName;
  private String phoneNumber;
  private String address;
  private String suburb;
  private String cityCouncil;
  private String inspector;
  private LocalDate inspectionDate;
  private String inspectionTime;
  private String projects;
  private String schedulerRemarks;
  private String quotationTeamReport;
  private String fieldStatus;
  private String customerEmail;
  private String inspectorRemark;
  private String rawJson;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  @TableField(exist = false)
  private Integer workDurationMinutes;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getImportBatchId() { return importBatchId; }
  public void setImportBatchId(Long importBatchId) { this.importBatchId = importBatchId; }
  public Integer getRowNumber() { return rowNumber; }
  public void setRowNumber(Integer rowNumber) { this.rowNumber = rowNumber; }
  public String getDayName() { return dayName; }
  public void setDayName(String dayName) { this.dayName = dayName; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getMacId() { return macId; }
  public void setMacId(String macId) { this.macId = macId; }
  public String getSales() { return sales; }
  public void setSales(String sales) { this.sales = sales; }
  public String getFirstName() { return firstName; }
  public void setFirstName(String firstName) { this.firstName = firstName; }
  public String getLastName() { return lastName; }
  public void setLastName(String lastName) { this.lastName = lastName; }
  public String getCustomerName() { return customerName; }
  public void setCustomerName(String customerName) { this.customerName = customerName; }
  public String getPhoneNumber() { return phoneNumber; }
  public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
  public String getAddress() { return address; }
  public void setAddress(String address) { this.address = address; }
  public String getSuburb() { return suburb; }
  public void setSuburb(String suburb) { this.suburb = suburb; }
  public String getCityCouncil() { return cityCouncil; }
  public void setCityCouncil(String cityCouncil) { this.cityCouncil = cityCouncil; }
  public String getInspector() { return inspector; }
  public void setInspector(String inspector) { this.inspector = inspector; }
  public LocalDate getInspectionDate() { return inspectionDate; }
  public void setInspectionDate(LocalDate inspectionDate) { this.inspectionDate = inspectionDate; }
  public String getInspectionTime() { return inspectionTime; }
  public void setInspectionTime(String inspectionTime) { this.inspectionTime = inspectionTime; }
  public String getProjects() { return projects; }
  public void setProjects(String projects) { this.projects = projects; }
  public String getSchedulerRemarks() { return schedulerRemarks; }
  public void setSchedulerRemarks(String schedulerRemarks) { this.schedulerRemarks = schedulerRemarks; }
  public String getQuotationTeamReport() { return quotationTeamReport; }
  public void setQuotationTeamReport(String quotationTeamReport) { this.quotationTeamReport = quotationTeamReport; }
  public String getFieldStatus() { return fieldStatus; }
  public void setFieldStatus(String fieldStatus) { this.fieldStatus = fieldStatus; }
  public String getCustomerEmail() { return customerEmail; }
  public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
  public String getInspectorRemark() { return inspectorRemark; }
  public void setInspectorRemark(String inspectorRemark) { this.inspectorRemark = inspectorRemark; }
  public String getRawJson() { return rawJson; }
  public void setRawJson(String rawJson) { this.rawJson = rawJson; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
  public Integer getWorkDurationMinutes() { return workDurationMinutes; }
  public void setWorkDurationMinutes(Integer workDurationMinutes) { this.workDurationMinutes = workDurationMinutes; }
}

