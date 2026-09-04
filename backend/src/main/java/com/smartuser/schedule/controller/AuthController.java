package com.smartuser.schedule.controller;

import com.smartuser.schedule.common.ApiResponse;
import com.smartuser.schedule.config.AuthInterceptor;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.model.LoginRequest;
import com.smartuser.schedule.service.AuthService;
import com.smartuser.schedule.service.GoogleSheetAutoImportService;
import com.smartuser.schedule.service.SystemService;
import com.smartuser.schedule.service.TokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口控制器。
 *
 * 功能作用：
 * 1. 处理前端登录、当前登录用户信息查询、退出登录三个认证相关接口。
 * 2. Controller 只负责 HTTP 入参/出参转换，用户名密码校验交给 AuthService，token 签发和校验交给 TokenService。
 * 3. 登录成功后统一返回 token、当前用户、菜单、权限，前端路由和按钮权限均依赖该数据。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService authService;
  private final TokenService tokenService;
  private final SystemService systemService;
  private final GoogleSheetAutoImportService googleSheetAutoImportService;

  public AuthController(AuthService authService, TokenService tokenService, SystemService systemService,
                        GoogleSheetAutoImportService googleSheetAutoImportService) {
    this.authService = authService;
    this.tokenService = tokenService;
    this.systemService = systemService;
    this.googleSheetAutoImportService = googleSheetAutoImportService;
  }

  /**
   * 登录接口：校验用户名密码，生成登录 token，并返回前端初始化页面所需的用户、菜单、权限数据。
   */
  @PostMapping("/login")
  public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
    CurrentUser currentUser = authService.login(request.getUsername(), request.getPassword());
    String token = tokenService.createToken(currentUser);
    // 登录响应不等待 Google 网络；Admin/Manager 登录成功后由单线程后台任务检查并导入新 Week。
    googleSheetAutoImportService.importNewWeeksAfterLogin(currentUser);
    return ApiResponse.ok(sessionPayload(token, currentUser));
  }

  /**
   * 当前用户接口：根据 AuthInterceptor 写入 request 的 currentUser 返回当前会话信息。
   */
  @GetMapping("/me")
  public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
    CurrentUser currentUser = (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    return ApiResponse.ok(sessionPayload(null, currentUser));
  }

  /**
   * 退出登录接口：从 Authorization 请求头中取出 Bearer token，并从 token 存储中移除。
   */
  @PostMapping("/logout")
  public ApiResponse<Void> logout(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      tokenService.remove(authorization.substring("Bearer ".length()).trim());
    }
    return ApiResponse.ok(null);
  }

  private Map<String, Object> sessionPayload(String token, CurrentUser currentUser) {
    // 前端登录态初始化统一使用该结构，避免 login 和 me 两个接口返回字段不一致。
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    if (token != null) {
      data.put("token", token);
    }
    data.put("user", currentUser);
    data.put("menus", systemService.listMenus());
    data.put("permissions", systemService.listRolePermissions(currentUser.getRoleCode()));
    return data;
  }
}
