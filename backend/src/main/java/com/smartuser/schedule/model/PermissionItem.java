package com.smartuser.schedule.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 权限项实体。
 *
 * 功能作用：
 * 1. 映射 sys_permission 表，保存系统功能权限编码和展示名称。
 * 2. 权限编码可用于前端按钮/功能点控制，也可被角色权限关系引用。
 */
@TableName("sys_permission")
public class PermissionItem {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String code;
  private String name;
  private String description;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
}
