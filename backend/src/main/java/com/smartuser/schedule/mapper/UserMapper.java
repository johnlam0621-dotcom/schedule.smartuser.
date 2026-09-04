package com.smartuser.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartuser.schedule.model.UserAccount;

/**
 * 系统用户单表 Mapper。
 *
 * 功能作用：
 * 1. 对应 sys_user 表，支撑登录认证、用户列表、用户新增/编辑、密码重置。
 * 2. 密码字段只在认证和保存密码时使用，Controller 返回前会由 SystemService 清空。
 * 3. 当前为单表查询和更新，使用 MyBatis-Plus BaseMapper。
 */
public interface UserMapper extends BaseMapper<UserAccount> {
}
