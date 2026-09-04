package com.smartuser.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartuser.schedule.model.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenServiceTest {
  @Test
  void restoresSessionFromDatabaseWhenRedisIsUnavailable() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper objectMapper = new ObjectMapper();
    CurrentUser expected = new CurrentUser();
    expected.setId(7L);
    expected.setUsername("manager");
    expected.setRealName("Manager");
    expected.setRoleCode("manager");
    when(redis.opsForValue()).thenThrow(new IllegalStateException("Redis offline"));
    when(jdbc.queryForList(anyString(), eq(String.class), anyString()))
        .thenReturn(Collections.singletonList(objectMapper.writeValueAsString(expected)));
    TokenService service = new TokenService(redis, jdbc, objectMapper, 28800L);

    CurrentUser actual = service.findUser("persisted-token");

    assertThat(actual).isNotNull();
    assertThat(actual.getId()).isEqualTo(7L);
    assertThat(actual.getUsername()).isEqualTo("manager");
    assertThat(actual.getRoleCode()).isEqualTo("manager");
  }
}
