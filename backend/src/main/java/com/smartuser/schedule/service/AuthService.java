package com.smartuser.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.mapper.UserMapper;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.model.UserAccount;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 登录认证业务服务。
 *
 * 功能作用：
 * 1. 根据用户名查找用户，校验账号状态和密码是否正确。
 * 2. 将数据库用户对象转换成前端会话中使用的 CurrentUser，避免把密码哈希等敏感字段返回给前端。
 * 3. 当前服务只处理认证业务，token 的生成和存储由 TokenService 负责。
 */
@Service
public class AuthService {
  private final UserMapper userMapper;
  private final PasswordService passwordService;

  public AuthService(UserMapper userMapper, PasswordService passwordService) {
    this.userMapper = userMapper;
    this.passwordService = passwordService;
  }

  public CurrentUser login(String username, String password) {
    // 登录入口必须同时校验用户名、密码、账号启用状态，任何失败都返回统一业务异常。
    if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
      throw new BadRequestException("Username and password are required.");
    }
    UserAccount user = findByUsername(username.trim());
    if (user == null || user.getStatus() == null || user.getStatus() != 1) {
      throw new BadRequestException("User is disabled or does not exist.");
    }
    if (!passwordService.matches(password, user.getPasswordHash())) {
      throw new BadRequestException("Invalid username or password.");
    }
    return toCurrentUser(user);
  }

  public UserAccount findByUsername(String username) {
    // 用户名是登录唯一标识，使用 MyBatis-Plus 单表查询即可满足当前场景。
    return userMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
        .eq(UserAccount::getUsername, username));
  }

  public CurrentUser toCurrentUser(UserAccount user) {
    // 只保留前端会话需要的身份字段，不返回 passwordHash 等敏感数据。
    CurrentUser currentUser = new CurrentUser();
    currentUser.setId(user.getId());
    currentUser.setUsername(user.getUsername());
    currentUser.setRealName(user.getRealName());
    currentUser.setRoleCode(user.getRoleCode());
    return currentUser;
  }
}
