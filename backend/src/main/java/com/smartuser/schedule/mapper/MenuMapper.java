package com.smartuser.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartuser.schedule.model.MenuItem;

/**
 * 菜单配置单表 Mapper。
 *
 * 功能作用：
 * 1. 对应 sys_menu 表，用于维护前端左侧菜单的名称、路径、排序、可见性。
 * 2. SystemService 根据 sort_order 和 id 排序返回菜单，前端按结果渲染导航。
 * 3. 当前为单表维护，使用 MyBatis-Plus BaseMapper。
 */
public interface MenuMapper extends BaseMapper<MenuItem> {
}
