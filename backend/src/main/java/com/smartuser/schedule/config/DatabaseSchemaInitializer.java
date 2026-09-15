package com.smartuser.schedule.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库结构初始化器。
 *
 * 功能作用：
 * 1. 后端启动时检查巡检记录表是否具备更新时间字段。
 * 2. 对历史数据库自动补齐 updated_at，并把旧数据的更新时间回填为创建时间。
 * 3. 自动补齐 Book 菜单和权限，避免老数据库启动后前端菜单缺少新增入口。
 */
@Component
public class DatabaseSchemaInitializer implements ApplicationRunner {
  private static final Logger LOG = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);

  private final JdbcTemplate jdbcTemplate;

  public DatabaseSchemaInitializer(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    ensureInspectionUpdatedAtColumn();
    ensureBookMenuAndPermission();
    ensureInspectorWorkspace();
    ensureSchedulerLimitedPermissions();
    ensureSalesLimitedPermissions();
    ensureQuotationRole();
    ensureManagerRoleAndPermissions();
  }

  /**
   * 巡检员工作台只使用本地 MySQL：Google Sheet 仍然只负责导入，不会在这里被修改。
   */
  private void ensureInspectorWorkspace() {
    try {
      jdbcTemplate.update(
          "INSERT INTO sys_role(code, name, description) VALUES('inspector', 'Inspector', 'Mobile route and shift workspace') " +
              "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description)");
      jdbcTemplate.update(
          "INSERT INTO sys_permission(code, name, description) VALUES('inspector:workspace', 'Inspector workspace', 'Use assigned route, shifts and uploads') " +
              "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description)");
      jdbcTemplate.update(
          "DELETE FROM sys_role_permission WHERE role_code = 'inspector' AND permission_code <> 'inspector:workspace'");
      jdbcTemplate.update(
          "INSERT INTO sys_role_permission(role_code, permission_code) VALUES('inspector', 'inspector:workspace') " +
              "ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code)");

      if (!columnExists("schedule_inspection", "field_status")) {
        jdbcTemplate.execute("ALTER TABLE schedule_inspection ADD COLUMN field_status VARCHAR(40) NULL COMMENT 'Inspector field status'");
      }
      if (!columnExists("schedule_inspection", "customer_email")) {
        jdbcTemplate.execute("ALTER TABLE schedule_inspection ADD COLUMN customer_email VARCHAR(240) NULL COMMENT 'Customer email'");
      }
      if (!columnExists("schedule_inspection", "inspector_remark")) {
        jdbcTemplate.execute("ALTER TABLE schedule_inspection ADD COLUMN inspector_remark TEXT NULL COMMENT 'Inspector remark'");
      }
      if (!columnExists("schedule_inspection", "manager_confirmed")) {
        jdbcTemplate.execute("ALTER TABLE schedule_inspection ADD COLUMN manager_confirmed TINYINT NOT NULL DEFAULT 0 COMMENT 'Manager weekly review confirmation'");
      }
      if (!columnExists("schedule_inspection", "manager_confirmed_by")) {
        jdbcTemplate.execute("ALTER TABLE schedule_inspection ADD COLUMN manager_confirmed_by VARCHAR(120) NULL COMMENT 'Manager who confirmed the inspection'");
      }
      if (!columnExists("schedule_inspection", "manager_confirmed_at")) {
        jdbcTemplate.execute("ALTER TABLE schedule_inspection ADD COLUMN manager_confirmed_at DATETIME NULL COMMENT 'Manager confirmation time'");
      }
      jdbcTemplate.execute(
          "CREATE TABLE IF NOT EXISTS inspector_shift (" +
              "id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, inspector_name VARCHAR(120) NOT NULL, " +
              "shift_date DATE NOT NULL, working TINYINT NOT NULL DEFAULT 1, start_time VARCHAR(5), end_time VARCHAR(5), " +
              "locked TINYINT NOT NULL DEFAULT 1, submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
              "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
              "UNIQUE KEY uk_inspector_shift_user_date(user_id, shift_date), INDEX idx_inspector_shift_date(shift_date)) " +
              "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
      jdbcTemplate.execute(
          "CREATE TABLE IF NOT EXISTS inspection_photo (" +
              "id BIGINT PRIMARY KEY AUTO_INCREMENT, inspection_id BIGINT NOT NULL, category_key VARCHAR(100) NOT NULL, " +
              "category_label VARCHAR(180) NOT NULL, original_name VARCHAR(255) NOT NULL, stored_name VARCHAR(255) NOT NULL, " +
              "content_type VARCHAR(120), file_size BIGINT NOT NULL, uploaded_by BIGINT NOT NULL, " +
              "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_inspection_photo_record(inspection_id)) " +
              "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
      if (!columnExists("inspection_photo", "remark")) {
        jdbcTemplate.execute("ALTER TABLE inspection_photo ADD COLUMN remark TEXT NULL COMMENT 'Photo remark'");
      }
      LOG.info("Inspector workspace roles, columns, and tables verified");
    } catch (Exception ex) {
      LOG.warn("Inspector workspace initialization is temporarily unavailable; backend startup will continue error={}", ex.getMessage());
    }
  }

  private void ensureInspectionUpdatedAtColumn() {
    try {
      boolean columnCreated = false;
      if (!columnExists("schedule_inspection", "updated_at")) {
        LOG.info("schedule_inspection is missing updated_at; adding the column");
        jdbcTemplate.execute("ALTER TABLE schedule_inspection ADD COLUMN updated_at DATETIME NULL COMMENT 'Updated time'");
        columnCreated = true;
        LOG.info("schedule_inspection.updated_at column added");
      }
      int filledRows = jdbcTemplate.update(
          "UPDATE schedule_inspection SET updated_at = COALESCE(created_at, NOW()) WHERE updated_at IS NULL");
      LOG.info("schedule_inspection.updated_at historical values backfilled rows={}", filledRows);
      if (columnCreated) {
        jdbcTemplate.execute("ALTER TABLE schedule_inspection MODIFY COLUMN updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time'");
        LOG.info("schedule_inspection.updated_at default update rule verified");
      }
    } catch (Exception ex) {
      // 远程 MySQL 短暂不可达时不应终止整个后端；连接池会在后续请求时继续尝试恢复连接。
      LOG.warn("Unable to verify schedule_inspection.updated_at temporarily; backend startup will continue error={}", ex.getMessage());
    }
  }

  private boolean columnExists(String tableName, String columnName) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
        Integer.class,
        tableName,
        columnName);
    return count != null && count > 0;
  }

  private void ensureBookMenuAndPermission() {
    try {
      LOG.info("Verifying the Book menu and route:book permission");
      jdbcTemplate.update(
          "INSERT INTO sys_permission(code, name, description) VALUES('route:book', 'Book appointment', 'Book route inspection appointments') " +
              "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description)");
      jdbcTemplate.update(
          "INSERT INTO sys_role_permission(role_code, permission_code) VALUES('admin', 'route:book') " +
              "ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code)");
      jdbcTemplate.update(
          "INSERT INTO sys_role_permission(role_code, permission_code) VALUES('scheduler', 'route:book') " +
              "ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code)");
      Long routesMapMenuId = jdbcTemplate.query(
          "SELECT id FROM sys_menu WHERE path = '/routes/map' LIMIT 1",
          resultSet -> resultSet.next() ? resultSet.getLong(1) : 0L);
      jdbcTemplate.update(
          "INSERT INTO sys_menu(parent_id, name, path, component, permission_code, sort_order, visible) " +
              "VALUES(?, 'Book', '/routes/book', 'BookView', 'route:book', 61, 1) " +
              "ON DUPLICATE KEY UPDATE parent_id = VALUES(parent_id), name = VALUES(name), component = VALUES(component), permission_code = VALUES(permission_code), sort_order = VALUES(sort_order), visible = VALUES(visible)",
          routesMapMenuId == null ? 0L : routesMapMenuId);
      LOG.info("Book menu and route:book permission verified");
    } catch (Exception ex) {
      // 菜单已经写入数据库时，瞬时网络故障不应让健康检查和静态接口一起退出。
      LOG.warn("Unable to verify the Book menu temporarily; backend startup will continue error={}", ex.getMessage());
    }
  }

  private void ensureManagerRoleAndPermissions() {
    try {
      LOG.info("Verifying the Manager role without confirmation approval access");
      jdbcTemplate.update(
          "INSERT INTO sys_role(code, name, description) VALUES('manager', 'Manager', 'System access excluding inspection confirmation') " +
              "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description)");
      jdbcTemplate.update(
          "DELETE FROM sys_role_permission WHERE role_code = 'manager' " +
              "AND permission_code IN ('system:menu', 'system:permission')");
      jdbcTemplate.update(
          "INSERT INTO sys_role_permission(role_code, permission_code) " +
              "SELECT 'manager', permission_code FROM sys_role_permission WHERE role_code = 'admin' " +
              "AND permission_code NOT IN ('system:menu', 'system:permission') " +
              "ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code)");
      jdbcTemplate.update(
          "INSERT INTO sys_menu(parent_id, name, path, component, permission_code, sort_order, visible) " +
              "VALUES(0, 'Inspection Done', '/inspections/confirm', 'InspectionConfirmationView', NULL, 72, 1) " +
              "ON DUPLICATE KEY UPDATE name = VALUES(name), component = VALUES(component), sort_order = VALUES(sort_order), visible = VALUES(visible)");
      LOG.info("Manager role permissions verified");
    } catch (Exception ex) {
      LOG.warn("Unable to verify the Manager role temporarily; backend startup will continue error={}", ex.getMessage());
    }
  }

  private void ensureQuotationRole() {
    try {
      LOG.info("Verifying the Quotation Team role");
      jdbcTemplate.update(
          "INSERT INTO sys_role(code, name, description) VALUES('quotation', 'Quotation Team', 'Inspector Photos and Inspection Done access') " +
              "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description)");
      jdbcTemplate.update("DELETE FROM sys_role_permission WHERE role_code = 'quotation'");
      LOG.info("Quotation Team role verified");
    } catch (Exception ex) {
      LOG.warn("Unable to verify the Quotation Team role temporarily; backend startup will continue error={}", ex.getMessage());
    }
  }

  private void ensureSchedulerLimitedPermissions() {
    try {
      LOG.info("Applying Scheduler page permissions for Search and Book");
      jdbcTemplate.update(
          "DELETE FROM sys_role_permission WHERE role_code = 'scheduler' " +
              "AND permission_code NOT IN ('inspection:list', 'route:book', 'inspector:workspace')");
      jdbcTemplate.update(
          "INSERT INTO sys_role_permission(role_code, permission_code) VALUES('scheduler', 'inspection:list') " +
              "ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code)");
      jdbcTemplate.update(
          "INSERT INTO sys_role_permission(role_code, permission_code) VALUES('scheduler', 'route:book') " +
              "ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code)");
      jdbcTemplate.update(
          "INSERT INTO sys_role_permission(role_code, permission_code) VALUES('scheduler', 'inspector:workspace') " +
              "ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code)");
      jdbcTemplate.update(
          "UPDATE sys_role SET name = 'Scheduler', description = 'Search, Book and personal Inspector workspace' WHERE code = 'scheduler'");
      LOG.info("Scheduler Search and Book page permissions applied");
    } catch (Exception ex) {
      LOG.warn("Unable to apply Scheduler permissions temporarily; backend startup will continue error={}", ex.getMessage());
    }
  }

  private void ensureSalesLimitedPermissions() {
    try {
      LOG.info("Verifying the Sales role with Scheduler-equivalent permissions");
      jdbcTemplate.update(
          "INSERT INTO sys_role(code, name, description) VALUES('sales', 'Sales', 'Search and Book access only') " +
              "ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description)");
      jdbcTemplate.update(
          "DELETE FROM sys_role_permission WHERE role_code = 'sales' " +
              "AND permission_code NOT IN ('inspection:list', 'route:book')");
      jdbcTemplate.update(
          "INSERT INTO sys_role_permission(role_code, permission_code) VALUES('sales', 'inspection:list') " +
              "ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code)");
      jdbcTemplate.update(
          "INSERT INTO sys_role_permission(role_code, permission_code) VALUES('sales', 'route:book') " +
              "ON DUPLICATE KEY UPDATE permission_code = VALUES(permission_code)");
      LOG.info("Sales role and Scheduler-equivalent permissions verified");
    } catch (Exception ex) {
      LOG.warn("Unable to verify Sales permissions temporarily; backend startup will continue error={}", ex.getMessage());
    }
  }
}
