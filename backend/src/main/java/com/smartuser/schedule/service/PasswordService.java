package com.smartuser.schedule.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 密码处理服务。
 *
 * 功能作用：
 * 1. 统一封装密码匹配和密码加密，业务层不直接依赖 BCrypt 细节。
 * 2. 兼容历史 {noop} 明文标记、BCrypt 哈希和老数据明文密码，便于旧数据逐步迁移。
 * 3. 新增和重置密码时一律使用 BCrypt 加密后再入库。
 */
@Service
public class PasswordService {
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public boolean matches(String rawPassword, String storedPassword) {
    // 登录校验同时兼容多种历史存储格式，避免老账号无法登录。
    if (!StringUtils.hasText(rawPassword) || !StringUtils.hasText(storedPassword)) {
      return false;
    }
    if (storedPassword.startsWith("{noop}")) {
      return rawPassword.equals(storedPassword.substring("{noop}".length()));
    }
    if (storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$") || storedPassword.startsWith("$2y$")) {
      return encoder.matches(rawPassword, storedPassword);
    }
    return rawPassword.equals(storedPassword);
  }

  public String encode(String rawPassword) {
    // 新密码统一生成 BCrypt 哈希，不再写入明文。
    return encoder.encode(rawPassword);
  }
}
