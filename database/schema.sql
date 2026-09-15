-- ==========================================
-- 删除所有表（按依赖关系顺序）
-- ==========================================
DROP TABLE IF EXISTS sys_role_permission;
DROP TABLE IF EXISTS sys_menu;
DROP TABLE IF EXISTS sys_auth_session;
DROP TABLE IF EXISTS schedule_inspection;
DROP TABLE IF EXISTS schedule_import_batch;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS sys_permission;
DROP TABLE IF EXISTS sys_role;

-- ==========================================
-- 1. 系统角色表
-- ==========================================
CREATE TABLE IF NOT EXISTS sys_role (
    code VARCHAR(50) PRIMARY KEY COMMENT '角色编码',
    name VARCHAR(100) NOT NULL COMMENT '角色名称',
    description VARCHAR(255) COMMENT '角色描述',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色表';

-- ==========================================
-- 2. 系统用户表
-- ==========================================
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(80) NOT NULL UNIQUE COMMENT '用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希',
    real_name VARCHAR(120) NOT NULL COMMENT '真实姓名',
    role_code VARCHAR(50) NOT NULL COMMENT '角色编码',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-启用，0-禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT fk_sys_user_role FOREIGN KEY (role_code) REFERENCES sys_role(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- 登录会话表：Redis 不可用时仍可跨后端重启恢复登录状态。
-- 只保存 token 的 SHA-256 摘要，不保存浏览器持有的原始 token。
CREATE TABLE IF NOT EXISTS sys_auth_session (
    token_hash CHAR(64) PRIMARY KEY COMMENT '登录 token SHA-256',
    session_json TEXT NOT NULL COMMENT '当前用户会话 JSON',
    expires_at DATETIME NOT NULL COMMENT '过期时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_sys_auth_session_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录会话表';

-- ==========================================
-- 3. 系统权限表
-- ==========================================
CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '权限ID',
    code VARCHAR(120) NOT NULL UNIQUE COMMENT '权限编码',
    name VARCHAR(120) NOT NULL COMMENT '权限名称',
    description VARCHAR(255) COMMENT '权限描述',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限表';

-- ==========================================
-- 4. 角色权限关联表
-- ==========================================
CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_code VARCHAR(50) NOT NULL COMMENT '角色编码',
    permission_code VARCHAR(120) NOT NULL COMMENT '权限编码',
    PRIMARY KEY (role_code, permission_code),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_code) REFERENCES sys_role(code),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_code) REFERENCES sys_permission(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- ==========================================
-- 5. 系统菜单表
-- ==========================================
CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '菜单ID',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '父菜单ID',
    name VARCHAR(120) NOT NULL COMMENT '菜单名称',
    path VARCHAR(200) NOT NULL COMMENT '菜单路径',
    component VARCHAR(120) COMMENT '前端组件',
    permission_code VARCHAR(120) COMMENT '权限编码',
    sort_order INT NOT NULL DEFAULT 100 COMMENT '排序号',
    visible TINYINT NOT NULL DEFAULT 1 COMMENT '是否可见：1-是，0-否',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_sys_menu_path (path),
    INDEX idx_sys_menu_sort (sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统菜单表';

-- ==========================================
-- 6. 导入批次表
-- ==========================================
CREATE TABLE IF NOT EXISTS schedule_import_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '批次ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_type VARCHAR(30) NOT NULL COMMENT '文件类型',
    total_rows INT NOT NULL DEFAULT 0 COMMENT '总行数',
    success_rows INT NOT NULL DEFAULT 0 COMMENT '成功行数',
    failed_rows INT NOT NULL DEFAULT 0 COMMENT '失败行数',
    created_by VARCHAR(80) COMMENT '创建人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导入批次表';

-- ==========================================
-- 7. 检查记录表
-- ==========================================
CREATE TABLE IF NOT EXISTS schedule_inspection (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    import_batch_id BIGINT COMMENT '导入批次ID',
    row_num INT COMMENT '行号',
    day_of_week VARCHAR(120) COMMENT '星期',
    status VARCHAR(120) COMMENT '状态',
    mac_id VARCHAR(80) COMMENT 'MAC地址',
    sales VARCHAR(160) COMMENT '销售',
    first_name VARCHAR(120) COMMENT '名',
    last_name VARCHAR(120) COMMENT '姓',
    customer_name VARCHAR(240) COMMENT '客户名称',
    phone_number VARCHAR(60) COMMENT '电话号码',
    address VARCHAR(500) COMMENT '地址',
    suburb VARCHAR(160) COMMENT '郊区',
    city_council VARCHAR(160) COMMENT '市议会',
    inspector VARCHAR(120) COMMENT '检查员',
    inspection_date DATE COMMENT '检查日期',
    inspection_time VARCHAR(60) COMMENT '检查时间',
    projects VARCHAR(160) COMMENT '项目',
    scheduler_remarks TEXT COMMENT '调度员备注',
    quotation_team_report TEXT COMMENT '报价团队报告',
    field_status VARCHAR(40) COMMENT '巡检员现场状态',
    customer_email VARCHAR(240) COMMENT '客户邮箱',
    inspector_remark TEXT COMMENT '巡检员备注',
    raw_json LONGTEXT COMMENT '原始JSON数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_schedule_inspection_batch (import_batch_id),
    INDEX idx_schedule_inspection_date (inspection_date),
    INDEX idx_schedule_inspection_inspector (inspector),
    INDEX idx_schedule_inspection_mac (mac_id),
    CONSTRAINT fk_schedule_inspection_batch FOREIGN KEY (import_batch_id) REFERENCES schedule_import_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='检查记录表';

-- 巡检员下一周班次；提交后由经理调整，巡检员不能重复修改。
CREATE TABLE IF NOT EXISTS inspector_shift (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    inspector_name VARCHAR(120) NOT NULL,
    shift_date DATE NOT NULL,
    working TINYINT NOT NULL DEFAULT 1,
    start_time VARCHAR(5),
    end_time VARCHAR(5),
    locked TINYINT NOT NULL DEFAULT 1,
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inspector_shift_user_date(user_id, shift_date),
    INDEX idx_inspector_shift_date(shift_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='巡检员班次提交';

-- 照片只保存在本应用；不会同步或写入 Google Sheet。
CREATE TABLE IF NOT EXISTS inspection_photo (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    inspection_id BIGINT NOT NULL,
    category_key VARCHAR(100) NOT NULL,
    category_label VARCHAR(180) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    stored_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(120),
    file_size BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    remark TEXT NULL COMMENT '单张照片备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_inspection_photo_record(inspection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='巡检员现场照片';

-- ==========================================
-- 8. 插入初始数据
-- ==========================================

-- 插入角色数据
INSERT INTO sys_role(code, name, description) VALUES
('admin', 'Administrator', 'Full system access'),
('manager', 'Manager', 'System access excluding inspection confirmation'),
('quotation', 'Quotation Team', 'Inspector Photos and Inspection Done access'),
('scheduler', 'Scheduler', 'Search, Book and personal Inspector workspace'),
('sales', 'Sales', 'Search and Book access only'),
('inspector', 'Inspector', 'Mobile route and shift workspace'),
('viewer', 'Viewer', 'Read-only route map access')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description);

-- 插入权限数据
INSERT INTO sys_permission(code, name, description) VALUES
('system:user', 'User management', 'Manage login users'),
('system:menu', 'Menu management', 'Manage menus'),
('system:permission', 'Permission management', 'Manage permissions'),
('inspection:import', 'Inspection import', 'Import csv/xls/xlsx inspection files'),
('inspection:list', 'Inspection list', 'View imported inspection records'),
('route:map', 'Routes map', 'View route map records'),
('route:book', 'Book appointment', 'Book route inspection appointments')
,
('inspector:workspace', 'Inspector workspace', 'Use assigned route, shifts and uploads')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description);

-- 插入角色权限关联数据
INSERT INTO sys_role_permission(role_code, permission_code) VALUES
('admin', 'system:user'),
('admin', 'system:menu'),
('admin', 'system:permission'),
('admin', 'inspection:import'),
('admin', 'inspection:list'),
('admin', 'route:map'),
('admin', 'route:book'),
('manager', 'system:user'),
('manager', 'inspection:import'),
('manager', 'inspection:list'),
('manager', 'route:map'),
('manager', 'route:book'),
('scheduler', 'inspection:list'),
('scheduler', 'route:book'),
('scheduler', 'inspector:workspace'),
('sales', 'inspection:list'),
('sales', 'route:book'),
('inspector', 'inspector:workspace'),
('viewer', 'inspection:list'),
('viewer', 'route:map')
ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code);

-- 不在可分享 SQL 中写入默认密码。
-- 首次启动前临时设置 SCHEDULE_BOOTSTRAP_ADMIN_PASSWORD，后端会创建/恢复管理员并使用 BCrypt 保存密码。

-- 插入菜单数据
INSERT INTO sys_menu(parent_id, name, path, component, permission_code, sort_order, visible) VALUES
(0, 'Dashboard', '/dashboard', 'DashboardHome', NULL, 10, 1),
(0, 'User Management', '/system/users', 'UsersView', 'system:user', 20, 1),
(0, 'Menu Management', '/system/menus', 'MenusView', 'system:menu', 30, 1),
(0, 'Permission Management', '/system/permissions', 'PermissionsView', 'system:permission', 40, 1),
(0, 'Inspection Import', '/inspections/import', 'ImportView', 'inspection:import', 50, 1),
(0, 'Routes map', '/routes/map', 'RoutesMapView', 'route:map', 60, 1),
(0, 'Book', '/routes/book', 'BookView', 'route:book', 61, 1)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    path = VALUES(path),
    component = VALUES(component),
    permission_code = VALUES(permission_code),
    sort_order = VALUES(sort_order),
    visible = VALUES(visible);

-- Book 作为 Routes map 的子菜单显示。
UPDATE sys_menu AS book_menu
JOIN sys_menu AS routes_menu ON routes_menu.path = '/routes/map'
SET book_menu.parent_id = routes_menu.id
WHERE book_menu.path = '/routes/book';
