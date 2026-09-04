package com.smartuser.schedule.config;

import com.smartuser.schedule.common.ApiResponse;
import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.common.ForbiddenException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 *
 * 功能作用：
 * 1. 将业务异常、唯一键冲突、数据库连接异常和未知异常统一包装成 ApiResponse。
 * 2. 前端始终能按 success/message 结构读取错误提示，不直接暴露 Spring 默认错误页。
 * 3. 所有异常都会输出 error 日志，便于排查接口调用链路和数据库连接问题。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * 业务校验异常统一返回 400，同时输出 error 日志，便于定位前端参数或业务规则问题。
   */
  @ExceptionHandler(BadRequestException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> badRequest(BadRequestException exception) {
    LOG.warn("Request validation failed: {}", exception.getMessage());
    return ApiResponse.fail(exception.getMessage());
  }

  @ExceptionHandler(ForbiddenException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ApiResponse<Void> forbidden(ForbiddenException exception) {
    LOG.warn("Permission validation failed: {}", exception.getMessage());
    return ApiResponse.fail(exception.getMessage());
  }

  @ExceptionHandler(DateTimeParseException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> invalidDate(DateTimeParseException exception) {
    LOG.warn("Invalid date format: {}", exception.getParsedString());
    return ApiResponse.fail("Invalid date. Use yyyy-MM-dd.");
  }

  /**
   * JSON 缺失、截断或格式错误属于客户端参数错误，不能误报为服务器故障。
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> malformedJson(HttpMessageNotReadableException exception) {
    LOG.warn("Malformed request JSON: {}", exception.getMessage());
    return ApiResponse.fail("Invalid request body. Check the JSON format and required fields.");
  }

  /**
   * 唯一索引冲突通常来自用户名、权限编码等重复录入，前端显示通用重复提示。
   */
  @ExceptionHandler(DuplicateKeyException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> duplicate(DuplicateKeyException exception) {
    LOG.error("Duplicate data: {}", exception.getMessage(), exception);
    return ApiResponse.fail("Duplicated record.");
  }

  /**
   * 数据库连接类异常不把底层堆栈暴露给前端，只返回可读的中文提示。
   */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ApiResponse<Void> error(Exception exception) {
    if (hasCause(exception, CannotGetJdbcConnectionException.class)
        || hasCause(exception, SQLTransientConnectionException.class)
        || hasCause(exception, SQLNonTransientConnectionException.class)) {
      LOG.error("Database connection error: {}", exception.getMessage(), exception);
      return ApiResponse.fail("The database connection timed out. Please try again later or check the MySQL connection.");
    }
    LOG.error("Unexpected system error: {}", exception.getMessage(), exception);
    return ApiResponse.fail("Unexpected server error. Check the backend log for details.");
  }

  private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
    // 遍历异常链，识别被 MyBatis/Spring 包装后的底层 JDBC 连接异常。
    Throwable current = throwable;
    while (current != null) {
      if (causeType.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
