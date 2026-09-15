package com.smartuser.schedule.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.http.Cookie;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
  void mediaCookieAuthenticatesOnlyReadOnlyPhotoRequests() throws Exception {
    TokenService tokenService = mock(TokenService.class);
    CurrentUser manager = new CurrentUser();
    manager.setRoleCode("manager");
    when(tokenService.findUser("media-token")).thenReturn(manager);
    AuthInterceptor interceptor = new AuthInterceptor(tokenService, new ObjectMapper());

    MockHttpServletRequest media = new MockHttpServletRequest("GET", "/api/inspector/photos/42");
    media.setCookies(new Cookie(AuthInterceptor.MEDIA_SESSION_COOKIE, "media-token"));
    assertThat(interceptor.preHandle(media, new MockHttpServletResponse(), new Object())).isTrue();

    MockHttpServletRequest playback = new MockHttpServletRequest("GET", "/api/inspector/photos/42/playback");
    playback.setCookies(new Cookie(AuthInterceptor.MEDIA_SESSION_COOKIE, "media-token"));
    assertThat(interceptor.preHandle(playback, new MockHttpServletResponse(), new Object())).isTrue();

    MockHttpServletRequest mutation = new MockHttpServletRequest("DELETE", "/api/inspector/photos/42");
    mutation.setCookies(new Cookie(AuthInterceptor.MEDIA_SESSION_COOKIE, "media-token"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertThat(interceptor.preHandle(mutation, response, new Object())).isFalse();
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void bearerAuthenticationCreatesHttpOnlyMediaCookie() throws Exception {
    TokenService tokenService = mock(TokenService.class);
    CurrentUser manager = new CurrentUser();
    manager.setRoleCode("manager");
    when(tokenService.findUser("browser-token")).thenReturn(manager);
    AuthInterceptor interceptor = new AuthInterceptor(tokenService, new ObjectMapper());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/inspector/photo-review");
    request.addHeader("Authorization", "Bearer browser-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    assertThat(response.getHeader("Set-Cookie"))
        .contains(AuthInterceptor.MEDIA_SESSION_COOKIE + "=browser-token")
        .contains("HttpOnly")
        .contains("SameSite=Strict")
        .contains("Path=/api/inspector/photos");
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

  @Test
  void quotationTeamCanOnlyReviewPhotosAndConfirmInspections() throws Exception {
    AuthInterceptor interceptor = new AuthInterceptor(null, new ObjectMapper());
    Method method = AuthInterceptor.class.getDeclaredMethod(
        "roleCanAccess", CurrentUser.class, String.class, String.class);
    method.setAccessible(true);
    CurrentUser quotation = new CurrentUser();
    quotation.setRoleCode("quotation");

    assertThat(method.invoke(interceptor, quotation, "GET", "/api/inspector/photo-review")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "GET", "/api/inspector/weekly-confirmations")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "GET", "/api/inspector/inspection-done/search")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "PUT", "/api/inspector/weekly-confirmations/42")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "GET", "/api/inspector/photos/42/playback")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "POST", "/api/inspector/photos/42/playback/prepare")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "PUT", "/api/inspector/photos/42/remark")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "DELETE", "/api/inspector/photos/42")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "POST", "/api/inspector/route/42/photos")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "POST", "/api/inspector/route/42/photos/chunk")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "POST", "/api/inspector/route/42/photos/complete")).isEqualTo(true);
    assertThat(method.invoke(interceptor, quotation, "GET", "/api/inspector/shifts/week")).isEqualTo(false);
    assertThat(method.invoke(interceptor, quotation, "GET", "/api/system/users")).isEqualTo(false);
  }

  @Test
  void managerCanReviewPhotosButCannotConfirmInspections() throws Exception {
    AuthInterceptor interceptor = new AuthInterceptor(null, new ObjectMapper());
    Method method = AuthInterceptor.class.getDeclaredMethod(
        "roleCanAccess", CurrentUser.class, String.class, String.class);
    method.setAccessible(true);
    CurrentUser manager = new CurrentUser();
    manager.setRoleCode("manager");

    assertThat(method.invoke(interceptor, manager, "GET", "/api/inspector/photo-review")).isEqualTo(true);
    assertThat(method.invoke(interceptor, manager, "GET", "/api/inspector/photos/42")).isEqualTo(true);
    assertThat(method.invoke(interceptor, manager, "GET", "/api/inspector/weekly-confirmations")).isEqualTo(false);
    assertThat(method.invoke(interceptor, manager, "GET", "/api/inspector/inspection-done/search")).isEqualTo(false);
    assertThat(method.invoke(interceptor, manager, "PUT", "/api/inspector/weekly-confirmations/42")).isEqualTo(false);
  }
}
