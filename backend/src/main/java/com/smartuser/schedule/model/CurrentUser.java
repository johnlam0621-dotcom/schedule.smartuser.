package com.smartuser.schedule.model;

/**
 * 当前登录用户会话对象。
 *
 * 功能作用：
 * 1. 登录成功后写入 token 会话，并在接口鉴权通过后放入 request。
 * 2. 只包含前端需要的用户身份字段，不包含密码或其他敏感信息。
 */
public class CurrentUser {
  private Long id;
  private String username;
  private String realName;
  private String roleCode;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getRealName() { return realName; }
  public void setRealName(String realName) { this.realName = realName; }
  public String getRoleCode() { return roleCode; }
  public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
}
