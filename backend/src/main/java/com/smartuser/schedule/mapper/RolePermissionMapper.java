package com.smartuser.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartuser.schedule.model.RolePermissionItem;

/**
 * 角色权限关系单表 Mapper。
 *
 * 功能作用：
 * 1. 对应 sys_role_permission 表，维护 role_code 与 permission_code 的授权关系。
 * 2. 登录成功或刷新当前用户信息时，SystemService 会按角色查询权限编码列表返回给前端。
 * 3. 当前为单表查询，使用 MyBatis-Plus BaseMapper。
 */
public interface RolePermissionMapper extends BaseMapper<RolePermissionItem> {
}
