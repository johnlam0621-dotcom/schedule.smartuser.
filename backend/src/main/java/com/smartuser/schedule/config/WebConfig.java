package com.smartuser.schedule.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC Web 配置。
 *
 * 功能作用：
 * 1. 注册 AuthInterceptor，让所有 /api/** 接口默认受登录态保护。
 * 2. 配置本地前端开发所需的跨域策略，允许 Vue dev server 调用后端接口。
 * 3. 暴露 Authorization 响应头，便于后续扩展 token 刷新等认证能力。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AuthInterceptor authInterceptor;

  public WebConfig(AuthInterceptor authInterceptor) {
    this.authInterceptor = authInterceptor;
  }

  /**
   * 注册接口鉴权拦截器，所有 /api/** 请求统一进入 AuthInterceptor。
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
  }

  /**
   * 配置跨域访问规则，支持本地 Vue 开发服务器调用后端 API。
   */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
        .allowedOriginPatterns("*")
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .exposedHeaders("Authorization")
        .allowCredentials(false)
        .maxAge(3600);
  }
}
