package com.smartuser.schedule.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 角色权限关系实体。
 *
 * 功能作用：
 * 1. 映射 sys_role_permission 表，保存角色编码与权限编码的对应关系。
 * 2. 登录返回当前角色权限列表时使用该表查询 permissionCode。
 */
@TableName("sys_role_permission")
public class RolePermissionItem {
  @TableId(value = "role_code", type = IdType.INPUT)
  private String roleCode;
  private String permissionCode;

  public String getRoleCode() { return roleCode; }
  public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
  public String getPermissionCode() { return permissionCode; }
  public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }
}
