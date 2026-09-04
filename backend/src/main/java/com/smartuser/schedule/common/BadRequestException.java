package com.smartuser.schedule.common;

/**
 * 业务参数异常。
 *
 * 功能作用：
 * 1. Service 或 Controller 主动抛出该异常表示请求参数缺失、业务规则不满足或记录不存在。
 * 2. GlobalExceptionHandler 会把该异常统一转换为 HTTP 400 和 ApiResponse.fail。
 */
public class BadRequestException extends RuntimeException {
  public BadRequestException(String message) {
    super(message);
  }
}
