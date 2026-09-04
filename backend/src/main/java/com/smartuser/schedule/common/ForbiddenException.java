package com.smartuser.schedule.common;

/**
 * 当前登录用户没有执行目标操作的权限。
 */
public class ForbiddenException extends RuntimeException {
  public ForbiddenException(String message) {
    super(message);
  }
}
