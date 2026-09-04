package com.smartuser.schedule.config;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.common.ForbiddenException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * 接口和业务方法执行日志切面。
 *
 * 功能作用：
 * 1. 自动记录所有 @RestController 方法的请求方法、路径、处理器、用户、参数、耗时和异常。
 * 2. 自动记录所有 @Service 方法的业务入参、返回摘要、耗时和异常。
 * 3. 对密码、token、secret、私钥等敏感字段做脱敏，避免日志泄露敏感信息。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExecutionLogAspect {
  private static final Logger INTERFACE_LOG = LoggerFactory.getLogger("com.smartuser.schedule.interface");
  private static final Logger BUSINESS_LOG = LoggerFactory.getLogger("com.smartuser.schedule.business");
  private static final int MAX_TEXT_LENGTH = 180;
  private static final int MAX_COLLECTION_ITEMS = 5;

  /**
   * 接口层日志：记录请求入口、当前用户、入参摘要、耗时和异常堆栈。
   */
  @Around("within(@org.springframework.web.bind.annotation.RestController *)")
  public Object logInterface(ProceedingJoinPoint joinPoint) throws Throwable {
    long start = System.currentTimeMillis();
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    HttpServletRequest request = currentRequest();
    String requestMethod = request == null ? "-" : request.getMethod();
    String path = request == null ? "-" : request.getRequestURI();
    String user = currentUser(request);
    String handler = handlerName(signature);
    String args = formatArgs(signature, joinPoint.getArgs());
    INTERFACE_LOG.info("Request started method={} path={} handler={} user={} args={}", requestMethod, path, handler, user, args);
    try {
      Object result = joinPoint.proceed();
      long cost = System.currentTimeMillis() - start;
      INTERFACE_LOG.info("Request completed method={} path={} handler={} user={} cost={}ms result={}",
          requestMethod, path, handler, user, cost, summarizeValue("result", result));
      return result;
    } catch (Throwable ex) {
      long cost = System.currentTimeMillis() - start;
      if (isExpectedClientError(ex)) {
        INTERFACE_LOG.warn("Request rejected method={} path={} handler={} user={} cost={}ms error={}",
            requestMethod, path, handler, user, cost, ex.getMessage());
      } else {
        INTERFACE_LOG.error("Request failed method={} path={} handler={} user={} cost={}ms error={}",
            requestMethod, path, handler, user, cost, ex.getMessage(), ex);
      }
      throw ex;
    }
  }

  /**
   * 业务层日志：记录 Service 方法执行链路，便于定位导入、同步、编辑等业务问题。
   */
  @Around("within(@org.springframework.stereotype.Service *)")
  public Object logBusiness(ProceedingJoinPoint joinPoint) throws Throwable {
    long start = System.currentTimeMillis();
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    String method = handlerName(signature);
    String args = formatArgs(signature, joinPoint.getArgs());
    BUSINESS_LOG.info("Service call started method={} args={}", method, args);
    try {
      Object result = joinPoint.proceed();
      long cost = System.currentTimeMillis() - start;
      // 方法名包含 token/password 时连返回值也脱敏，避免记录新 token 或 BCrypt 哈希。
      BUSINESS_LOG.info("Service call completed method={} cost={}ms result={}", method, cost, summarizeValue(method, result));
      return result;
    } catch (Throwable ex) {
      long cost = System.currentTimeMillis() - start;
      if (isExpectedClientError(ex)) {
        BUSINESS_LOG.warn("Service call rejected method={} cost={}ms error={}", method, cost, ex.getMessage());
      } else {
        BUSINESS_LOG.error("Service call failed method={} cost={}ms error={}", method, cost, ex.getMessage(), ex);
      }
      throw ex;
    }
  }

  private boolean isExpectedClientError(Throwable throwable) {
    return throwable instanceof BadRequestException || throwable instanceof ForbiddenException;
  }

  private HttpServletRequest currentRequest() {
    // 从当前线程的 RequestContextHolder 中取出 HTTP 请求，非 Web 调用时返回 null。
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes) {
      return ((ServletRequestAttributes) attributes).getRequest();
    }
    return null;
  }

  private String currentUser(HttpServletRequest request) {
    // 读取 AuthInterceptor 写入的 currentUser，日志中只记录用户名或用户 id。
    if (request == null) {
      return "-";
    }
    Object value = request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    if (value instanceof CurrentUser) {
      CurrentUser user = (CurrentUser) value;
      return StringUtils.hasText(user.getUsername()) ? user.getUsername() : String.valueOf(user.getId());
    }
    return "-";
  }

  private String handlerName(MethodSignature signature) {
    // 日志使用 类名.方法名 作为处理器名称，便于快速定位源码。
    Method method = signature.getMethod();
    return method.getDeclaringClass().getSimpleName() + "." + method.getName();
  }

  private String formatArgs(MethodSignature signature, Object[] args) {
    // 参数名来自 Spring/AOP 方法签名，无法获取时使用 arg0/arg1 兜底。
    if (args == null || args.length == 0) {
      return "[]";
    }
    String[] names = signature.getParameterNames();
    StringBuilder builder = new StringBuilder("[");
    for (int i = 0; i < args.length; i++) {
      if (i > 0) {
        builder.append(", ");
      }
      String name = names != null && i < names.length && StringUtils.hasText(names[i]) ? names[i] : "arg" + i;
      builder.append(name).append("=").append(summarizeValue(name, args[i]));
    }
    builder.append("]");
    return builder.toString();
  }

  private String summarizeValue(String name, Object value) {
    // 日志摘要只保留排查所需信息，避免打印密码、token、secret 和超大对象。
    if (isSensitiveName(name)) {
      return "***";
    }
    if (value == null) {
      return "null";
    }
    if (value instanceof ServletRequest || value instanceof ServletResponse) {
      return value.getClass().getSimpleName();
    }
    if (value instanceof MultipartFile) {
      MultipartFile file = (MultipartFile) value;
      return "MultipartFile{name=" + safeText(file.getOriginalFilename()) + ", size=" + file.getSize() + "}";
    }
    if (value instanceof InputStream || value instanceof Reader) {
      return value.getClass().getSimpleName();
    }
    if (value instanceof byte[]) {
      return "byte[" + ((byte[]) value).length + "]";
    }
    if (value instanceof CharSequence) {
      return "\"" + safeText(String.valueOf(value)) + "\"";
    }
    if (value instanceof Number || value instanceof Boolean || value instanceof Enum || value instanceof TemporalAccessor) {
      return String.valueOf(value);
    }
    if (value.getClass().isArray()) {
      return summarizeArray(value);
    }
    if (value instanceof Collection) {
      return summarizeCollection((Collection<?>) value);
    }
    if (value instanceof Map) {
      return summarizeMap((Map<?, ?>) value);
    }
    if (value instanceof Page) {
      Page<?> page = (Page<?>) value;
      return "Page{current=" + page.getCurrent() + ", size=" + page.getSize() + ", total=" + page.getTotal() + ", records=" + page.getRecords().size() + "}";
    }
    return value.getClass().getSimpleName() + "{" + safeText(String.valueOf(value)) + "}";
  }

  private String summarizeArray(Object array) {
    // 数组日志只展示长度和前几个元素，避免导出文件或大列表刷屏。
    int length = Array.getLength(array);
    StringBuilder builder = new StringBuilder(array.getClass().getComponentType().getSimpleName())
        .append("[").append(length).append("]");
    if (length > 0) {
      builder.append("{");
      int limit = Math.min(length, MAX_COLLECTION_ITEMS);
      for (int i = 0; i < limit; i++) {
        if (i > 0) {
          builder.append(", ");
        }
        builder.append(summarizeValue("item", Array.get(array, i)));
      }
      if (length > limit) {
        builder.append(", ...");
      }
      builder.append("}");
    }
    return builder.toString();
  }

  private String summarizeCollection(Collection<?> collection) {
    // 集合日志只展示 size 和前几个样本，满足排查同时控制日志体积。
    StringBuilder builder = new StringBuilder(collection.getClass().getSimpleName())
        .append("{size=").append(collection.size());
    if (!collection.isEmpty()) {
      builder.append(", items=[");
      Iterator<?> iterator = collection.iterator();
      int count = 0;
      while (iterator.hasNext() && count < MAX_COLLECTION_ITEMS) {
        if (count > 0) {
          builder.append(", ");
        }
        builder.append(summarizeValue("item", iterator.next()));
        count++;
      }
      if (collection.size() > MAX_COLLECTION_ITEMS) {
        builder.append(", ...");
      }
      builder.append("]");
    }
    builder.append("}");
    return builder.toString();
  }

  private String summarizeMap(Map<?, ?> map) {
    // Map 日志按 key 做敏感字段判断，防止 body 中的 secret/password 被打印。
    StringBuilder builder = new StringBuilder(map.getClass().getSimpleName())
        .append("{size=").append(map.size());
    if (!map.isEmpty()) {
      builder.append(", entries=[");
      int count = 0;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (count >= MAX_COLLECTION_ITEMS) {
          builder.append(", ...");
          break;
        }
        if (count > 0) {
          builder.append(", ");
        }
        String key = String.valueOf(entry.getKey());
        builder.append(key).append("=").append(summarizeValue(key, entry.getValue()));
        count++;
      }
      builder.append("]");
    }
    builder.append("}");
    return builder.toString();
  }

  private boolean isSensitiveName(String name) {
    // 只根据字段名做轻量脱敏判断，覆盖密码、token、secret、私钥、服务账号等常见敏感名称。
    if (name == null) {
      return false;
    }
    String lower = name.toLowerCase();
    return lower.contains("password")
        || lower.contains("passwd")
        || lower.contains("token")
        || lower.contains("authorization")
        || lower.contains("secret")
        || lower.contains("privatekey")
        || lower.contains("private_key")
        || lower.contains("serviceaccount")
        || lower.contains("service_account");
  }

  private String safeText(String value) {
    // 文本日志去掉换行并截断，避免单条日志过长影响查看。
    if (value == null) {
      return "";
    }
    String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
    if (normalized.length() > MAX_TEXT_LENGTH) {
      return normalized.substring(0, MAX_TEXT_LENGTH) + "...";
    }
    return normalized;
  }
}
