package com.smartuser.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartuser.schedule.model.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 登录 token 会话服务。
 *
 * 功能作用：
 * 1. 登录成功后生成随机 token，并把 CurrentUser 会话写入 Redis。
 * 2. Redis 不可用时使用本地内存兜底，保证开发环境无需强依赖 Redis 也能登录。
 * 3. 每次读取 token 会刷新过期时间，退出登录时删除 Redis 和本地兜底会话。
 */
@Service
public class TokenService {
  private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);
  private static final long REDIS_RETRY_DELAY_MILLIS = 30000L;
  private final StringRedisTemplate redisTemplate;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final long ttlSeconds;
  private final Map<String, LocalSession> localSessions = new ConcurrentHashMap<String, LocalSession>();
  private volatile long redisRetryAfterMillis;

  public TokenService(StringRedisTemplate redisTemplate,
                      JdbcTemplate jdbcTemplate,
                      ObjectMapper objectMapper,
                      @Value("${schedule.auth.token-ttl-seconds:28800}") long ttlSeconds) {
    this.redisTemplate = redisTemplate;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.ttlSeconds = ttlSeconds;
  }

  @PostConstruct
  public void initializeSessionTable() {
    try {
      // token 只保存 SHA-256 摘要；数据库泄露时不会直接暴露可用登录凭据。
      jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sys_auth_session ("
          + "token_hash CHAR(64) PRIMARY KEY, session_json TEXT NOT NULL, "
          + "expires_at DATETIME NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
          + "INDEX idx_sys_auth_session_expires_at (expires_at)) "
          + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
      jdbcTemplate.update("DELETE FROM sys_auth_session WHERE expires_at <= CURRENT_TIMESTAMP");
    } catch (Exception ex) {
      // 旧数据库账号没有建表权限时仍可使用 Redis/内存，但会明确记录降级原因。
      LOG.warn("Unable to initialize the database login-session table; continuing with Redis/in-memory sessions: {}", ex.getMessage());
    }
  }

  public String createToken(CurrentUser user) {
    // token 使用 UUID 去掉横线生成，值本身不包含用户信息，降低泄露后的信息暴露面。
    String token = UUID.randomUUID().toString().replace("-", "");
    boolean persisted = storeDatabaseSession(token, user);
    if (!storeRedisSession(token, user) && !persisted) {
      // Redis 和数据库会话都不可用时才回退内存，保证登录功能仍能工作。
      localSessions.put(token, new LocalSession(user, Instant.now().getEpochSecond() + ttlSeconds));
    }
    return token;
  }

  public CurrentUser findUser(String token) {
    // 先查 Redis 主存储；读取成功后顺手续期，保持活跃用户登录态。
    if (!StringUtils.hasText(token)) {
      return null;
    }
    if (canTryRedis()) {
      try {
      String value = redisTemplate.opsForValue().get(redisKey(token));
      if (StringUtils.hasText(value)) {
        redisTemplate.expire(redisKey(token), ttlSeconds, TimeUnit.SECONDS);
        return objectMapper.readValue(value, CurrentUser.class);
      }
      } catch (Exception ex) {
        markRedisUnavailable();
      }
    }
    CurrentUser databaseUser = findDatabaseSession(token);
    if (databaseUser != null) {
      storeRedisSession(token, databaseUser);
      return databaseUser;
    }
    LocalSession session = localSessions.get(token);
    if (session == null || session.expiresAt < Instant.now().getEpochSecond()) {
      localSessions.remove(token);
      return null;
    }
    return session.user;
  }

  public void remove(String token) {
    // 退出登录要同时清理 Redis 和本地兜底缓存，防止旧 token 继续可用。
    if (!StringUtils.hasText(token)) {
      return;
    }
    if (canTryRedis()) {
      try {
        redisTemplate.delete(redisKey(token));
      } catch (Exception ex) {
        markRedisUnavailable();
      }
    }
    try {
      jdbcTemplate.update("DELETE FROM sys_auth_session WHERE token_hash = ?", tokenHash(token));
    } catch (Exception ex) {
      LOG.warn("Failed to delete the database login session: {}", ex.getMessage());
    }
    localSessions.remove(token);
  }

  private boolean storeRedisSession(String token, CurrentUser user) {
    if (!canTryRedis()) {
      return false;
    }
    try {
      redisTemplate.opsForValue().set(redisKey(token), objectMapper.writeValueAsString(user), ttlSeconds, TimeUnit.SECONDS);
      return true;
    } catch (Exception ex) {
      markRedisUnavailable();
      return false;
    }
  }

  private boolean storeDatabaseSession(String token, CurrentUser user) {
    try {
      String json = objectMapper.writeValueAsString(user);
      LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(ttlSeconds);
      jdbcTemplate.update("INSERT INTO sys_auth_session(token_hash, session_json, expires_at) VALUES (?, ?, ?) "
              + "ON DUPLICATE KEY UPDATE session_json = VALUES(session_json), expires_at = VALUES(expires_at)",
          tokenHash(token), json, expiresAt);
      return true;
    } catch (Exception ex) {
      LOG.warn("Failed to save the database login session: {}", ex.getMessage());
      return false;
    }
  }

  private CurrentUser findDatabaseSession(String token) {
    try {
      java.util.List<String> sessions = jdbcTemplate.queryForList(
          "SELECT session_json FROM sys_auth_session WHERE token_hash = ? AND expires_at > CURRENT_TIMESTAMP",
          String.class, tokenHash(token));
      if (sessions.isEmpty()) {
        return null;
      }
      jdbcTemplate.update("UPDATE sys_auth_session SET expires_at = ? WHERE token_hash = ?",
          LocalDateTime.now().plusSeconds(ttlSeconds), tokenHash(token));
      return objectMapper.readValue(sessions.get(0), CurrentUser.class);
    } catch (Exception ex) {
      LOG.warn("Failed to read the database login session: {}", ex.getMessage());
      return null;
    }
  }

  private boolean canTryRedis() {
    return System.currentTimeMillis() >= redisRetryAfterMillis;
  }

  private void markRedisUnavailable() {
    redisRetryAfterMillis = System.currentTimeMillis() + REDIS_RETRY_DELAY_MILLIS;
  }

  private String tokenHash(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot hash login token.", ex);
    }
  }

  private String redisKey(String token) {
    // 所有认证 token 使用统一前缀，便于 Redis 中定位和批量排查。
    return "schedule:auth:" + token;
  }

  private static class LocalSession {
    // 本地兜底会话只保存用户信息和过期时间，不参与跨进程共享。
    private final CurrentUser user;
    private final long expiresAt;

    private LocalSession(CurrentUser user, long expiresAt) {
      this.user = user;
      this.expiresAt = expiresAt;
    }
  }
}
