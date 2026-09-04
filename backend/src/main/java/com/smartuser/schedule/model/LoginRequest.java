package com.smartuser.schedule.model;

/**
 * 登录请求对象。
 *
 * 功能作用：
 * 1. 接收 /api/auth/login 请求体中的 username 和 password。
 * 2. 只作为入参模型使用，不会持久化到数据库。
 */
public class LoginRequest {
  private String username;
  private String password;

  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getPassword() { return password; }
  public void setPassword(String password) { this.password = password; }
}
