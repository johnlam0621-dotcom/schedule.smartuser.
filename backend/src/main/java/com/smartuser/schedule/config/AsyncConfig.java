package com.smartuser.schedule.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** 后台任务线程配置：Google Sheet 登录同步只使用一个线程，避免多人同时登录重复导入。 */
@Configuration
public class AsyncConfig {
  @Bean(name = "googleSheetAutoSyncExecutor")
  public Executor googleSheetAutoSyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(20);
    executor.setThreadNamePrefix("google-sheet-auto-");
    executor.initialize();
    return executor;
  }
}
