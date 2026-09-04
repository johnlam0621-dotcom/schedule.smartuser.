package com.smartuser.schedule.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuthInterceptorRoleTest {

  @Test
  void onlyTheExactLoginPathBypassesAuthentication() throws Exception {
    AuthInterceptor interceptor = new AuthInterceptor(mock(TokenService.class), new ObjectMapper());
    MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/auth/login");
    MockHttpServletResponse loginResponse = new MockHttpServletResponse();
    MockHttpServletRequest similarPath = new MockHttpServletRequest("POST", "/api/auth/login-anything");
    MockHttpServletResponse similarResponse = new MockHttpServletResponse();

    assertThat(interceptor.preHandle(login, loginResponse, new Object())).isTrue();
    assertThat(interceptor.preHandle(similarPath, similarResponse, new Object())).isFalse();
    assertThat(similarResponse.getStatus()).isEqualTo(401);
  }

  @Test
  void salesHasTheSameSearchAndBookAccessAsScheduler() throws Exception {
    // Sales 与 Scheduler 共用最小权限：允许 Search/Book 所需接口，拒绝系统管理接口。
    AuthInterceptor interceptor = new AuthInterceptor(null, new ObjectMapper());
    Method method = AuthInterceptor.class.getDeclaredMethod(
        "roleCanAccess", CurrentUser.class, String.class, String.class);
    method.setAccessible(true);
    CurrentUser sales = new CurrentUser();
    sales.setRoleCode("sales");

    assertThat(method.invoke(interceptor, sales, "POST", "/api/routes/book/slots")).isEqualTo(true);
    assertThat(method.invoke(interceptor, sales, "GET", "/api/inspections/records")).isEqualTo(true);
    assertThat(method.invoke(interceptor, sales, "GET", "/api/inspector/route")).isEqualTo(false);
    assertThat(method.invoke(interceptor, sales, "GET", "/api/system/users")).isEqualTo(false);
  }

  @Test
  void inspectorCanOnlyUseTheMobileWorkspaceApi() throws Exception {
    AuthInterceptor interceptor = new AuthInterceptor(null, new ObjectMapper());
    Method method = AuthInterceptor.class.getDeclaredMethod(
        "roleCanAccess", CurrentUser.class, String.class, String.class);
    method.setAccessible(true);
    CurrentUser inspector = new CurrentUser();
    inspector.setRoleCode("inspector");

    assertThat(method.invoke(interceptor, inspector, "GET", "/api/inspector/route")).isEqualTo(true);
    assertThat(method.invoke(interceptor, inspector, "POST", "/api/inspector/shifts")).isEqualTo(true);
    assertThat(method.invoke(interceptor, inspector, "GET", "/api/routes/map")).isEqualTo(false);
    assertThat(method.invoke(interceptor, inspector, "GET", "/api/system/users")).isEqualTo(false);
  }

  @Test
  void schedulerCanUseItsOwnInspectorWorkspace() throws Exception {
    AuthInterceptor interceptor = new AuthInterceptor(null, new ObjectMapper());
    Method method = AuthInterceptor.class.getDeclaredMethod(
        "roleCanAccess", CurrentUser.class, String.class, String.class);
    method.setAccessible(true);
    CurrentUser scheduler = new CurrentUser();
    scheduler.setRoleCode("scheduler");

    assertThat(method.invoke(interceptor, scheduler, "GET", "/api/inspector/route")).isEqualTo(true);
    assertThat(method.invoke(interceptor, scheduler, "POST", "/api/inspector/shifts")).isEqualTo(true);
  }
}
