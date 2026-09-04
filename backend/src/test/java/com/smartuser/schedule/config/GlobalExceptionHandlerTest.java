package com.smartuser.schedule.config;

import com.smartuser.schedule.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerTest {

  @Test
  void malformedJsonReturnsAReadableClientError() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    ApiResponse<Void> response = handler.malformedJson(new HttpMessageNotReadableException("broken JSON"));

    assertFalse(response.isSuccess());
    assertEquals("Invalid request body. Check the JSON format and required fields.", response.getMessage());
  }
}
