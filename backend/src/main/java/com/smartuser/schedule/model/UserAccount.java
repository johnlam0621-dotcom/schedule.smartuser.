package com.smartuser.schedule.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 系统用户实体。
 *
 * 功能作用：
 * 1. 映射 sys_user 表，保存登录账号、密码哈希、真实姓名、角色和启用状态。
 * 2. 登录认证读取 passwordHash，用户管理列表返回前会清空该字段。
 */
@TableName("sys_user")
public class UserAccount {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String username;
  private String passwordHash;
  private String realName;
  private String roleCode;
  private Integer status;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
  public String getRealName() { return realName; }
  public void setRealName(String realName) { this.realName = realName; }
  public String getRoleCode() { return roleCode; }
  public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
  public Integer getStatus() { return status; }
  public void setStatus(Integer status) { this.status = status; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
