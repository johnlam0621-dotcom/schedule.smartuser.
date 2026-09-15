package com.smartuser.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.mapper.MenuMapper;
import com.smartuser.schedule.mapper.PermissionMapper;
import com.smartuser.schedule.mapper.RolePermissionMapper;
import com.smartuser.schedule.mapper.UserMapper;
import com.smartuser.schedule.model.MenuItem;
import com.smartuser.schedule.model.PermissionItem;
import com.smartuser.schedule.model.RolePermissionItem;
import com.smartuser.schedule.model.UserAccount;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 系统基础数据业务服务。
 *
 * 功能作用：
 * 1. 维护用户、菜单、权限、角色权限等后台基础数据。
 * 2. 当前操作均为单表增删改查，按照项目约定优先使用 MyBatis-Plus Wrapper/BaseMapper 实现。
 * 3. 对外返回用户信息前统一清空密码字段，避免敏感信息泄露到前端页面。
 */
@Service
public class SystemService {
  private final UserMapper userMapper;
  private final MenuMapper menuMapper;
  private final PermissionMapper permissionMapper;
  private final RolePermissionMapper rolePermissionMapper;
  private final PasswordService passwordService;

  public SystemService(UserMapper userMapper,
                       MenuMapper menuMapper,
                       PermissionMapper permissionMapper,
                       RolePermissionMapper rolePermissionMapper,
                       PasswordService passwordService) {
    this.userMapper = userMapper;
    this.menuMapper = menuMapper;
    this.permissionMapper = permissionMapper;
    this.rolePermissionMapper = rolePermissionMapper;
    this.passwordService = passwordService;
  }

  public List<UserAccount> listUsers() {
    // 用户列表只查询页面展示需要的字段，避免把 password_hash 从数据库带出。
    List<UserAccount> users = userMapper.selectList(new LambdaQueryWrapper<UserAccount>()
        .select(UserAccount::getId, UserAccount::getUsername, UserAccount::getRealName, UserAccount::getRoleCode,
            UserAccount::getStatus, UserAccount::getCreatedAt, UserAccount::getUpdatedAt)
        .orderByAsc(UserAccount::getId));
    users.forEach(this::clearPassword);
    return users;
  }

  public UserAccount findUserById(Long id) {
    // 权限判断需要读取目标账号的角色；该对象只在后端内部使用，不返回 password_hash。
    return id == null ? null : userMapper.selectById(id);
  }

  public UserAccount saveUser(UserAccount user) {
    // 新增和编辑共用该方法：id 为空代表新增，id 不为空代表更新。
    if (!StringUtils.hasText(user.getUsername())) {
      throw new BadRequestException("Username is required.");
    }
    String username = user.getUsername().trim();
    String realName = StringUtils.hasText(user.getRealName()) ? user.getRealName().trim() : username;
    String roleCode = StringUtils.hasText(user.getRoleCode()) ? user.getRoleCode().trim() : "scheduler";
    Integer status = user.getStatus() == null ? 1 : user.getStatus();
    if (!Arrays.asList("admin", "manager", "quotation", "scheduler", "sales", "viewer", "inspector").contains(roleCode)) {
      throw new BadRequestException("Unsupported user role.");
    }
    if (status != 0 && status != 1) {
      throw new BadRequestException("User status must be Enabled or Disabled.");
    }
    // 在写库前主动检查用户名冲突，避免数据库唯一键异常被转换成不明确的 500。
    UserAccount duplicate = userMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
        .select(UserAccount::getId)
        .eq(UserAccount::getUsername, username));
    if (duplicate != null && (user.getId() == null || !duplicate.getId().equals(user.getId()))) {
      throw new BadRequestException("Username already exists.");
    }
    if (user.getId() == null) {
      if (!StringUtils.hasText(user.getPasswordHash())) {
        throw new BadRequestException("Password is required for a new user.");
      }
      String password = user.getPasswordHash();
      if (password.length() < 8) {
        throw new BadRequestException("Password must contain at least 8 characters.");
      }
      UserAccount insert = new UserAccount();
      insert.setUsername(username);
      insert.setPasswordHash(passwordService.encode(password));
      insert.setRealName(realName);
      insert.setRoleCode(roleCode);
      insert.setStatus(status);
      userMapper.insert(insert);
    } else {
      UserAccount update = new UserAccount();
      update.setId(user.getId());
      update.setUsername(username);
      update.setRealName(realName);
      update.setRoleCode(roleCode);
      update.setStatus(status);
      userMapper.updateById(update);
      if (StringUtils.hasText(user.getPasswordHash())) {
        resetPassword(user.getId(), user.getPasswordHash());
      }
    }
    UserAccount saved = userMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
        .select(UserAccount::getId, UserAccount::getUsername, UserAccount::getRealName, UserAccount::getRoleCode,
            UserAccount::getStatus, UserAccount::getCreatedAt, UserAccount::getUpdatedAt)
        .eq(UserAccount::getUsername, username));
    return clearPassword(saved);
  }

  public void resetPassword(Long id, String newPassword) {
    // 密码重置必须走加密存储，禁止把明文密码直接写入数据库。
    if (id == null || !StringUtils.hasText(newPassword)) {
      throw new BadRequestException("User id and new password are required.");
    }
    UserAccount update = new UserAccount();
    update.setId(id);
    update.setPasswordHash(passwordService.encode(newPassword));
    userMapper.updateById(update);
  }

  public List<MenuItem> listMenus() {
    // 菜单按 sortOrder 和 id 稳定排序，保证前端侧边栏展示顺序一致。
    return menuMapper.selectList(new LambdaQueryWrapper<MenuItem>()
        .orderByAsc(MenuItem::getSortOrder)
        .orderByAsc(MenuItem::getId));
  }

  public MenuItem saveMenu(MenuItem menu) {
    // 菜单保存前补齐默认父级、排序和可见状态，减少前端传参负担。
    if (!StringUtils.hasText(menu.getName()) || !StringUtils.hasText(menu.getPath())) {
      throw new BadRequestException("Menu name and path are required.");
    }
    if (menu.getParentId() == null) menu.setParentId(0L);
    if (menu.getSortOrder() == null) menu.setSortOrder(100);
    if (menu.getVisible() == null) menu.setVisible(1);
    if (menu.getId() == null) {
      menuMapper.insert(menu);
    } else {
      menuMapper.updateById(menu);
    }
    return menu;
  }

  public List<PermissionItem> listPermissions() {
    // 权限编码是权限判断的核心字段，按编码排序便于后台维护。
    return permissionMapper.selectList(new LambdaQueryWrapper<PermissionItem>()
        .orderByAsc(PermissionItem::getCode));
  }

  public PermissionItem savePermission(PermissionItem permission) {
    // 权限编码和名称都不能为空，保存前做 trim，避免空格造成重复或匹配失败。
    if (!StringUtils.hasText(permission.getCode()) || !StringUtils.hasText(permission.getName())) {
      throw new BadRequestException("Permission code and name are required.");
    }
    permission.setCode(permission.getCode().trim());
    permission.setName(permission.getName().trim());
    if (permission.getId() == null) {
      permissionMapper.insert(permission);
    } else {
      permissionMapper.updateById(permission);
    }
    return permission;
  }

  public List<String> listRolePermissions(String roleCode) {
    // 前端只需要当前角色拥有的权限编码列表，因此这里直接映射为 String 集合。
    return rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermissionItem>()
            .eq(RolePermissionItem::getRoleCode, roleCode)
            .orderByAsc(RolePermissionItem::getPermissionCode))
        .stream()
        .map(RolePermissionItem::getPermissionCode)
        .collect(Collectors.toList());
  }

  private UserAccount clearPassword(UserAccount user) {
    // 任何返回给前端的用户对象都不应包含密码哈希。
    if (user != null) {
      user.setPasswordHash("");
    }
    return user;
  }
}
