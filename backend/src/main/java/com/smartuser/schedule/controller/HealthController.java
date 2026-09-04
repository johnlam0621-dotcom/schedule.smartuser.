package com.smartuser.schedule.controller;

import com.smartuser.schedule.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查控制器。
 *
 * 功能作用：
 * 1. 给前端、启动脚本或部署探针提供最轻量的后端存活检查接口。
 * 2. 不依赖登录态，AuthInterceptor 会放行该路径，便于服务启动后快速判断接口是否可访问。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {
  /**
   * 服务状态接口：返回服务名和 UP 状态，用于确认 Spring Boot 应用已启动并可响应请求。
   */
  @GetMapping("/status")
  public ApiResponse<Map<String, Object>> health() {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("service", "schedule-smartuser");
    data.put("status", "UP");
    return ApiResponse.ok(data);
  }
}
