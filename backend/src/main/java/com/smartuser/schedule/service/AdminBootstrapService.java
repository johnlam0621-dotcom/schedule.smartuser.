package com.smartuser.schedule.service;

import com.smartuser.schedule.mapper.UserMapper;
import com.smartuser.schedule.model.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 管理员一次性初始化工具。
 *
 * 只有显式提供 SCHEDULE_BOOTSTRAP_ADMIN_PASSWORD 时才运行，避免在源码或导出包中保存默认密码。
 * 初始化完成后应删除该环境变量；已有账号会被恢复为启用状态并更新为 BCrypt 密码。
 */
@Component
public class AdminBootstrapService implements ApplicationRunner {
  private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrapService.class);

  private final UserMapper userMapper;
  private final PasswordService passwordService;
  private final String username;
  private final String password;
  private final String realName;

  public AdminBootstrapService(UserMapper userMapper,
                               PasswordService passwordService,
                               @Value("${schedule.bootstrap.admin.username:admin}") String username,
                               @Value("${schedule.bootstrap.admin.password:}") String password,
                               @Value("${schedule.bootstrap.admin.real-name:System Admin}") String realName) {
    this.userMapper = userMapper;
    this.passwordService = passwordService;
    this.username = username;
    this.password = password;
    this.realName = realName;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!StringUtils.hasText(password)) {
      return;
    }
    String normalizedUsername = StringUtils.hasText(username) ? username.trim() : "admin";
    UserAccount account = userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserAccount>()
        .eq(UserAccount::getUsername, normalizedUsername));
    if (account == null) {
      account = new UserAccount();
      account.setUsername(normalizedUsername);
      account.setRealName(StringUtils.hasText(realName) ? realName.trim() : "System Admin");
      account.setRoleCode("admin");
      account.setStatus(1);
      account.setPasswordHash(passwordService.encode(password));
      userMapper.insert(account);
      LOG.info("Administrator initialization completed username={}", normalizedUsername);
      return;
    }
    account.setRealName(StringUtils.hasText(realName) ? realName.trim() : account.getRealName());
    account.setRoleCode("admin");
    account.setStatus(1);
    account.setPasswordHash(passwordService.encode(password));
    userMapper.updateById(account);
    LOG.info("Administrator account restored and password reset username={}", normalizedUsername);
  }
}
