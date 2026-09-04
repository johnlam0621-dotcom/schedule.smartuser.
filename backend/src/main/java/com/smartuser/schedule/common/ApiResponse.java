package com.smartuser.schedule.common;

/**
 * 后端接口统一响应对象。
 *
 * 功能作用：
 * 1. 统一所有 REST 接口返回结构：success 表示是否成功，message 表示提示信息，data 表示业务数据。
 * 2. 前端 HTTP 拦截器可以只按这一套结构处理成功、失败和错误提示。
 */
public class ApiResponse<T> {
  private boolean success;
  private String message;
  private T data;

  public static <T> ApiResponse<T> ok(T data) {
    // 成功响应统一 message=ok，业务数据放入 data。
    ApiResponse<T> response = new ApiResponse<T>();
    response.setSuccess(true);
    response.setMessage("ok");
    response.setData(data);
    return response;
  }

  public static <T> ApiResponse<T> fail(String message) {
    // 失败响应只返回错误提示，data 保持为空。
    ApiResponse<T> response = new ApiResponse<T>();
    response.setSuccess(false);
    response.setMessage(message);
    return response;
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public T getData() {
    return data;
  }

  public void setData(T data) {
    this.data = data;
  }
}
