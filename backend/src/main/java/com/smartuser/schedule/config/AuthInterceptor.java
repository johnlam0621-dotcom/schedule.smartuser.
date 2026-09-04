package com.smartuser.schedule.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartuser.schedule.common.ApiResponse;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.service.TokenService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录鉴权拦截器。
 *
 * 功能作用：
 * 1. 拦截 /api/** 请求，校验 Authorization: Bearer token 是否有效。
 * 2. 登录接口和健康检查接口放行，OPTIONS 预检请求放行。
 * 3. token 校验成功后把 CurrentUser 写入 request，Controller 可直接读取当前用户。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
  public static final String CURRENT_USER_ATTRIBUTE = "currentUser";

  private final TokenService tokenService;
  private final ObjectMapper objectMapper;

  public AuthInterceptor(TokenService tokenService, ObjectMapper objectMapper) {
    this.tokenService = tokenService;
    this.objectMapper = objectMapper;
  }

  /**
   * 请求进入 Controller 前执行登录态校验，OPTIONS、登录接口和健康检查接口会直接放行。
   */
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    String path = request.getRequestURI();
    // 只放行精确的登录接口，避免未来新增相似路径时意外绕过 token 校验。
    if ("/api/auth/login".equals(path) || path.startsWith("/api/health")) {
      return true;
    }
    String authorization = request.getHeader("Authorization");
    String token = "";
    if (authorization != null && authorization.startsWith("Bearer ")) {
      token = authorization.substring("Bearer ".length()).trim();
    }
    CurrentUser currentUser = tokenService.findUser(token);
    if (currentUser == null) {
      // 未登录统一返回 401 和 ApiResponse JSON，前端拦截器会跳转登录页。
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail("Unauthorized")));
      return false;
    }
    // 前端菜单隐藏只能改善界面体验，不能替代后端权限校验。
    // Scheduler 只能使用 Search / Book 所需接口；Viewer 只能读取 Search / Routes map 数据。
    if (!roleCanAccess(currentUser, request.getMethod(), path)) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail("Forbidden")));
      return false;
    }
    // 将当前用户保存到 request，后续 Controller 可以直接读取。
    request.setAttribute(CURRENT_USER_ATTRIBUTE, currentUser);
    return true;
  }

  private boolean roleCanAccess(CurrentUser currentUser, String method, String path) {
    String roleCode = currentUser == null || currentUser.getRoleCode() == null
        ? ""
        : currentUser.getRoleCode().trim().toLowerCase();
    if ("admin".equals(roleCode) || "manager".equals(roleCode)) {
      return true;
    }
    if (path.startsWith("/api/auth/")) {
      return true;
    }
    if ("scheduler".equals(roleCode)) {
      return schedulerCanAccess(method, path);
    }
    if ("sales".equals(roleCode)) {
      return !path.startsWith("/api/inspector/") && schedulerCanAccess(method, path);
    }
    if ("viewer".equals(roleCode)) {
      return viewerCanAccess(method, path);
    }
    if ("inspector".equals(roleCode)) {
      return path.startsWith("/api/inspector/");
    }
    return false;
  }

  private boolean schedulerCanAccess(String method, String path) {
    if (path.startsWith("/api/inspector/")) {
      // Scheduler 同时承担现场 Inspector 工作，因此可使用自己的路线、班次和照片接口。
      return true;
    }
    if (path.startsWith("/api/routes/book/")) {
      return true;
    }
    if ("GET".equalsIgnoreCase(method) && "/api/routes/map".equals(path)) {
      // Book 页面读取当天排程，用于计算路线工作量和行车建议。
      return true;
    }
    return "GET".equalsIgnoreCase(method) && isInspectionRecordReadPath(path);
  }

  private boolean viewerCanAccess(String method, String path) {
    if ("GET".equalsIgnoreCase(method)
        && ("/api/routes/options".equals(path)
        || "/api/routes/map".equals(path)
        || "/api/routes/map-data".equals(path))) {
      return true;
    }
    if ("POST".equalsIgnoreCase(method) && "/api/routes/map/export".equals(path)) {
      return true;
    }
    return "GET".equalsIgnoreCase(method) && isInspectionRecordReadPath(path);
  }

  private boolean isInspectionRecordReadPath(String path) {
    return "/api/inspections/records".equals(path)
        || path.matches("/api/inspections/records/\\d+");
  }
}
