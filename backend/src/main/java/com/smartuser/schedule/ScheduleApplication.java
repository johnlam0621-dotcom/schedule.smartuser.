package com.smartuser.schedule;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Schedule SmartUser 后端启动入口。
 *
 * 功能作用：
 * 1. 启动 Spring Boot 应用，加载 Controller、Service、Mapper、配置类等组件。
 * 2. 通过 @MapperScan 扫描 MyBatis-Plus Mapper 接口，注册单表数据访问能力。
 */
@SpringBootApplication
@MapperScan("com.smartuser.schedule.mapper")
@EnableAsync
public class ScheduleApplication {
  public static void main(String[] args) {
    // 应用启动后默认监听 application.yml 中配置的 server.port。
    SpringApplication.run(ScheduleApplication.class, args);
  }
}
