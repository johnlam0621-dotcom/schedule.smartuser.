package com.smartuser.schedule.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * 功能作用：
 * 1. 注册分页插件，支持 Inspection Import 和 Routes map 列表的分页查询。
 * 2. 指定数据库类型为 MySQL，保证 LIMIT/OFFSET 分页 SQL 正确生成。
 * 3. 单表增删改查优先使用 MyBatis-Plus，复杂多表 SQL 再放到 XML 映射文件。
 */
@Configuration
public class MybatisPlusConfig {
  /**
   * 注册 MyBatis-Plus 分页拦截器，selectPage 查询会自动生成 MySQL 分页语句。
   */
  @Bean
  public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
  }
}
