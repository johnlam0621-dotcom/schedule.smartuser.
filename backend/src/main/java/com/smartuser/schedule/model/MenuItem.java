package com.smartuser.schedule.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 菜单配置实体。
 *
 * 功能作用：
 * 1. 映射 sys_menu 表，保存前端左侧导航菜单的名称、路径、组件、权限编码和排序。
 * 2. 登录响应会返回菜单列表，前端根据 visible 字段决定是否显示。
 */
@TableName("sys_menu")
public class MenuItem {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long parentId;
  private String name;
  private String path;
  private String component;
  private String permissionCode;
  private Integer sortOrder;
  private Integer visible;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getParentId() { return parentId; }
  public void setParentId(Long parentId) { this.parentId = parentId; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }
  public String getComponent() { return component; }
  public void setComponent(String component) { this.component = component; }
  public String getPermissionCode() { return permissionCode; }
  public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public Integer getVisible() { return visible; }
  public void setVisible(Integer visible) { this.visible = visible; }
}
