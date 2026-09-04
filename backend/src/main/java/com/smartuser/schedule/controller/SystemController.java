package com.smartuser.schedule.controller;

import com.smartuser.schedule.common.ApiResponse;
import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.common.ForbiddenException;
import com.smartuser.schedule.config.AuthInterceptor;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.model.MenuItem;
import com.smartuser.schedule.model.PermissionItem;
import com.smartuser.schedule.model.UserAccount;
import com.smartuser.schedule.service.SystemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;

/**
 * 系统管理控制器。
 *
 * 功能作用：
 * 1. 提供用户、菜单、权限的基础维护接口，支撑左侧菜单、用户管理、权限管理页面。
 * 2. 单表新增/修改/查询交给 SystemService，Controller 层负责 REST 路径和参数绑定。
 * 3. 密码重置单独使用 reset-password 接口，避免普通用户编辑时误改密码。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {
  private final SystemService systemService;

  public SystemController(SystemService systemService) {
    this.systemService = systemService;
  }

  /**
   * 用户列表接口：查询系统用户基础信息，密码字段会在 Service 层清空后再返回。
   */
  @GetMapping("/users")
  public ApiResponse<List<UserAccount>> users(HttpServletRequest request) {
    requireUserManager(request);
    return ApiResponse.ok(systemService.listUsers());
  }

  /**
   * 创建用户接口：前端不传 id，后端使用默认角色/状态和加密后的默认密码创建用户。
   */
  @PostMapping("/users")
  public ApiResponse<UserAccount> createUser(@RequestBody UserAccount user, HttpServletRequest request) {
    CurrentUser currentUser = requireUserManager(request);
    preventManagerAdminEscalation(currentUser, user, null);
    user.setId(null);
    return ApiResponse.ok(systemService.saveUser(user));
  }

  /**
   * 更新用户接口：根据路径 id 更新用户名、真实姓名、角色、状态等基础资料。
   */
  @PutMapping("/users/{id}")
  public ApiResponse<UserAccount> updateUser(@PathVariable Long id, @RequestBody UserAccount user,
                                              HttpServletRequest request) {
    CurrentUser currentUser = requireUserManager(request);
    preventManagerAdminEscalation(currentUser, user, id);
    user.setId(id);
    return ApiResponse.ok(systemService.saveUser(user));
  }

  /**
   * 重置密码接口：只处理密码更新，防止与用户资料编辑混在一起造成误操作。
   */
  @PostMapping("/users/{id}/reset-password")
  public ApiResponse<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body,
                                         HttpServletRequest request) {
    CurrentUser currentUser = requireUserManager(request);
    preventManagerAdminEscalation(currentUser, null, id);
    String password = body == null ? "" : body.get("password");
    if (password == null || password.trim().isEmpty()) {
      throw new BadRequestException("Password is required.");
    }
    systemService.resetPassword(id, password);
    return ApiResponse.ok(null);
  }

  /**
   * 菜单列表接口：返回系统菜单配置，前端左侧导航按该结果渲染。
   */
  @GetMapping("/menus")
  public ApiResponse<List<MenuItem>> menus(HttpServletRequest request) {
    requireAdmin(request);
    return ApiResponse.ok(systemService.listMenus());
  }

  /**
   * 创建菜单接口：新增菜单名称、路径、排序、可见性等配置。
   */
  @PostMapping("/menus")
  public ApiResponse<MenuItem> createMenu(@RequestBody MenuItem menu, HttpServletRequest request) {
    requireAdmin(request);
    menu.setId(null);
    return ApiResponse.ok(systemService.saveMenu(menu));
  }

  /**
   * 更新菜单接口：按路径 id 更新已有菜单配置。
   */
  @PutMapping("/menus/{id}")
  public ApiResponse<MenuItem> updateMenu(@PathVariable Long id, @RequestBody MenuItem menu,
                                          HttpServletRequest request) {
    requireAdmin(request);
    menu.setId(id);
    return ApiResponse.ok(systemService.saveMenu(menu));
  }

  /**
   * 权限列表接口：返回按钮/操作权限编码，供权限管理页面维护。
   */
  @GetMapping("/permissions")
  public ApiResponse<List<PermissionItem>> permissions(HttpServletRequest request) {
    requireAdmin(request);
    return ApiResponse.ok(systemService.listPermissions());
  }

  /**
   * 创建权限接口：新增权限编码和权限名称，权限编码需要保持唯一。
   */
  @PostMapping("/permissions")
  public ApiResponse<PermissionItem> createPermission(@RequestBody PermissionItem permission,
                                                       HttpServletRequest request) {
    requireAdmin(request);
    permission.setId(null);
    return ApiResponse.ok(systemService.savePermission(permission));
  }

  /**
   * 更新权限接口：按路径 id 更新权限编码或显示名称。
   */
  @PutMapping("/permissions/{id}")
  public ApiResponse<PermissionItem> updatePermission(@PathVariable Long id,
                                                       @RequestBody PermissionItem permission,
                                                       HttpServletRequest request) {
    requireAdmin(request);
    permission.setId(id);
    return ApiResponse.ok(systemService.savePermission(permission));
  }

  private void requireAdmin(HttpServletRequest request) {
    CurrentUser currentUser =
        (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    if (currentUser == null || !"admin".equals(currentUser.getRoleCode())) {
      throw new ForbiddenException("Only administrators can manage menus and permissions.");
    }
  }

  private CurrentUser requireUserManager(HttpServletRequest request) {
    // 用户管理允许 Admin 和 Manager 使用；Scheduler/Viewer 即使直接调用 API 也会被拒绝。
    CurrentUser currentUser =
        (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    String roleCode = currentUser == null ? "" : currentUser.getRoleCode();
    if (!"admin".equals(roleCode) && !"manager".equals(roleCode)) {
      throw new ForbiddenException("Only administrators and managers can manage users.");
    }
    return currentUser;
  }

  private void preventManagerAdminEscalation(CurrentUser currentUser, UserAccount requestedUser, Long targetId) {
    if (currentUser == null || !"manager".equals(currentUser.getRoleCode())) {
      return;
    }
    // Manager 可以维护普通账号，但不能创建 Admin，也不能修改/重置现有 Admin，避免间接取得系统配置权限。
    if (requestedUser != null && "admin".equals(requestedUser.getRoleCode())) {
      throw new ForbiddenException("Managers cannot create or promote administrator accounts.");
    }
    UserAccount existing = targetId == null ? null : systemService.findUserById(targetId);
    if (existing != null && "admin".equals(existing.getRoleCode())) {
      throw new ForbiddenException("Managers cannot modify administrator accounts.");
    }
  }
}
