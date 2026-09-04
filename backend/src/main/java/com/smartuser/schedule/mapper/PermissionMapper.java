package com.smartuser.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartuser.schedule.model.PermissionItem;

/**
 * 权限项单表 Mapper。
 *
 * 功能作用：
 * 1. 对应 sys_permission 表，用于维护权限编码和权限名称。
 * 2. 权限编码会被角色权限表引用，并返回给前端做按钮或功能点权限判断。
 * 3. 当前为单表维护，使用 MyBatis-Plus BaseMapper。
 */
public interface PermissionMapper extends BaseMapper<PermissionItem> {
}
