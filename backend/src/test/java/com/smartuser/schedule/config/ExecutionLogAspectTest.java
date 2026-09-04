package com.smartuser.schedule.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionLogAspectTest {

  @Test
  void generatedTokensAndPasswordHashesAreMasked() throws Exception {
    ExecutionLogAspect aspect = new ExecutionLogAspect();
    Method summarize = ExecutionLogAspect.class.getDeclaredMethod("summarizeValue", String.class, Object.class);
    summarize.setAccessible(true);

    assertThat(summarize.invoke(aspect, "TokenService.createToken", "raw-session-token")).isEqualTo("***");
    assertThat(summarize.invoke(aspect, "PasswordService.encode", "$2a$10$hash")).isEqualTo("***");
    assertThat(summarize.invoke(aspect, "ordinaryResult", "safe-value")).isEqualTo("\"safe-value\"");
  }
}
