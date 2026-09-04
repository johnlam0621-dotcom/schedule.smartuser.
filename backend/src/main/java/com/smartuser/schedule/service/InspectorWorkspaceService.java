package com.smartuser.schedule.service;

import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.model.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/**
 * 巡检员移动工作台业务。
 * 所有修改只写本地 MySQL 和本地上传目录，不调用 Google Sheet 写接口。
 */
@Service
public class InspectorWorkspaceService {
  private static final Logger LOG = LoggerFactory.getLogger(InspectorWorkspaceService.class);
  private static final List<String[]> PHOTO_SLOTS = Arrays.asList(
      new String[]{"compulsory_photo", "Compulsory Photo", "standard"},
      new String[]{"customer_acknowledgment", "Customer Acknowledgment", "standard"},
      new String[]{"price_match_quotation", "Price Match Quotation", "standard"},
      new String[]{"switchboard", "Switchboard", "standard"},
      new String[]{"switchboard_main_switch", "Switchboard Main Switch (Close Up)", "standard"},
      new String[]{"ducted_gas_vents", "Ducted Gas/Vents", "standard"},
      new String[]{"premises_roof", "Premises/Roof", "standard"},
      new String[]{"indoor_location_1", "Indoor Location 1", "standard"},
      new String[]{"indoor_location_2", "Indoor Location 2", "standard"},
      new String[]{"indoor_location_3", "Indoor Location 3", "standard"},
      new String[]{"indoor_location_4", "Indoor Location 4", "standard"},
      new String[]{"condenser_outdoor_location", "Condenser Outdoor Location", "standard"},
      new String[]{"mac_floor_plan", "Floor Plan", "mac_plan"},
      new String[]{"mac_measurements", "Measurements", "mac_plan"},
      new String[]{"drawn_floor_plan_measurements", "Floor Plan", "standard"},
      new String[]{"battery_measurements", "Measurements", "battery_sales"},
      new String[]{"signed_documents", "Signed Documents", "standard"},
      // 电池与太阳能使用独立分类，前端可与空调、热泵照片分开显示。
      new String[]{"battery_unit", "Full Photo of the Switchboard", "battery_solar"},
      new String[]{"battery_serial_label", "Photo of Indoor Area for Battery", "battery_solar"},
      new String[]{"battery_location", "Photo of Outdoor Area for Battery", "battery_solar"},
      new String[]{"battery_connection", "Photo of Solar Panels / Inverter", "battery_solar"},
      new String[]{"solar_vic_approval", "Solar Victoria Approval", "battery_solar"},
      new String[]{"solar_vic_qr", "Solar Victoria QR", "battery_solar"},
      new String[]{"battery_pre_approval", "Pre-Approval", "battery_solar"},
      new String[]{"battery_indoor_outdoor_video", "Indoor / Outdoor Area Video", "battery_video"},
      // 热泵照片保留稳定 category_key，后续增加报告或审核规则时无需迁移旧文件。
      new String[]{"heat_pump_unit", "Heat Pump Unit", "heat_pump"},
      new String[]{"heat_pump_model_label", "Heat Pump Model / Serial Label", "heat_pump"},
      new String[]{"heat_pump_location", "Heat Pump Installation Location", "heat_pump"},
      new String[]{"heat_pump_connections", "Pipework and Connections", "heat_pump"},
      new String[]{"heat_pump_switchboard", "Heat Pump Switchboard", "heat_pump"},
      new String[]{"heat_pump_completed", "Completed Heat Pump Installation", "heat_pump"},
      new String[]{"additional_photo_1", "Additional Photo 1", "additional"},
      new String[]{"additional_photo_2", "Additional Photo 2", "additional"},
      new String[]{"additional_photo_3", "Additional Photo 3", "additional"},
      new String[]{"additional_photo_4", "Additional Photo 4", "additional"},
      new String[]{"additional_photo_5", "Additional Photo 5", "additional"},
      new String[]{"additional_photo_6", "Additional Photo 6", "additional"},
      new String[]{"additional_photo_7", "Additional Photo 7", "additional"},
      new String[]{"additional_photo_8", "Additional Photo 8", "additional"},
      new String[]{"additional_photo_9", "Additional Photo 9", "additional"},
      new String[]{"additional_photo_10", "Additional Photo 10", "additional"},
      new String[]{"inspection_video_1", "Inspection Video 1", "video"},
      new String[]{"inspection_video_2", "Inspection Video 2", "video"},
      new String[]{"inspection_video_3", "Inspection Video 3", "video"},
      new String[]{"inspection_video_4", "Inspection Video 4", "video"}
  );
  private static final List<String[]> REQUIRED_COMPLETION_PHOTOS = Arrays.asList(
      new String[]{"switchboard", "Switchboard"},
      new String[]{"switchboard_main_switch", "Switchboard Main Switch (Close Up)"},
      new String[]{"ducted_gas_vents", "Ducted Gas/Vents"},
      new String[]{"premises_roof", "Premises/Roof"},
      new String[]{"indoor_location_1", "Indoor Location 1"},
      new String[]{"indoor_location_2", "Indoor Location 2"},
      new String[]{"indoor_location_3", "Indoor Location 3"},
      new String[]{"indoor_location_4", "Indoor Location 4"},
      new String[]{"condenser_outdoor_location", "Condenser Outdoor Location"}
  );
  private static final Set<String> SAFE_IMAGE_TYPES = Set.of(
      "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif");
  private static final Set<String> SAFE_VIDEO_TYPES = Set.of(
      "video/mp4", "video/quicktime", "video/webm", "video/x-m4v", "video/3gpp");

  private final JdbcTemplate jdbcTemplate;
  private final Path uploadRoot;

  public InspectorWorkspaceService(JdbcTemplate jdbcTemplate,
                                   @Value("${schedule.inspector.upload-dir:uploads}") String uploadDir) {
    this.jdbcTemplate = jdbcTemplate;
    this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
  }

  public Map<String, Object> route(CurrentUser user, LocalDate date) {
    requireInspectorName(user);
    String assignedInspectorName = assignedInspectorName(user);
    List<Map<String, Object>> stops = loadStops(date, assignedInspectorName);

    int done = (int) stops.stream().filter(item -> "done".equals(item.get("fieldStatus"))).count();
    String next = stops.stream()
        .filter(item -> !"done".equals(item.get("fieldStatus")) && !"customer_unavailable".equals(item.get("fieldStatus")))
        .map(item -> String.valueOf(item.get("start"))).findFirst().orElse("--:--");
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("inspectorName", user.getRealName());
    response.put("date", date.toString());
    response.put("area", stops.isEmpty() ? "Assigned area" : value(stops.get(0).get("suburb")));
    response.put("stopCount", stops.size());
    response.put("doneCount", done);
    response.put("nextTime", next);
    response.put("stops", stops);
    return response;
  }

  /** 管理员照片审核页只读取本地数据库和上传目录，不修改路线或 Google Sheet。 */
  public Map<String, Object> photoReview(CurrentUser user, String inspector, LocalDate date) {
    requireManager(user);
    List<String> inspectors = jdbcTemplate.queryForList(
        "SELECT DISTINCT TRIM(inspector) FROM schedule_inspection " +
            "WHERE COALESCE(TRIM(inspector), '') <> '' ORDER BY TRIM(inspector)", String.class);
    String selectedInspector = value(inspector).trim();
    List<String> availableDates = StringUtils.hasText(selectedInspector)
        ? jdbcTemplate.queryForList(
            "SELECT DISTINCT DATE_FORMAT(inspection_date, '%Y-%m-%d') FROM schedule_inspection " +
                "WHERE LOWER(TRIM(inspector)) = LOWER(TRIM(?)) " +
                "AND COALESCE(LOWER(status), '') NOT IN ('cancelled', 'canceled', 'open slot') " +
                "AND (COALESCE(TRIM(customer_name), '') <> '' OR COALESCE(TRIM(first_name), '') <> '' " +
                "OR COALESCE(TRIM(last_name), '') <> '' OR COALESCE(TRIM(mac_id), '') <> '' " +
                "OR COALESCE(TRIM(phone_number), '') <> '' OR COALESCE(TRIM(address), '') <> '') " +
                "ORDER BY inspection_date DESC",
            String.class, selectedInspector)
        : List.of();
    LocalDate effectiveDate = date;
    if (!availableDates.isEmpty() && !availableDates.contains(date.toString())) {
      effectiveDate = LocalDate.parse(availableDates.get(0));
    }
    List<Map<String, Object>> jobs = StringUtils.hasText(selectedInspector)
        ? loadStops(effectiveDate, selectedInspector) : List.of();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("inspectors", inspectors);
    response.put("inspectorName", selectedInspector);
    response.put("date", effectiveDate.toString());
    response.put("availableDates", availableDates);
    response.put("jobCount", jobs.size());
    response.put("fileCount", jobs.stream().mapToInt(job -> ((List<?>) job.get("photoSlots")).stream()
        .mapToInt(slot -> ((List<?>) ((Map<?, ?>) slot).get("files")).size()).sum()).sum());
    response.put("jobs", jobs);
    return response;
  }

  /** Weekly manager sign-off view, grouped by inspector and backed only by the local inspection database. */
  public Map<String, Object> weeklyConfirmations(CurrentUser user, LocalDate selectedDate) {
    requireManager(user);
    LocalDate weekStart = selectedDate.with(DayOfWeek.MONDAY);
    LocalDate weekEnd = weekStart.plusDays(6);
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT id, inspector, mac_id, customer_name, first_name, last_name, address, suburb, " +
            "inspection_date, inspection_time, projects, scheduler_remarks, inspector_remark, field_status, " +
            "manager_confirmed, manager_confirmed_by, manager_confirmed_at " +
            "FROM schedule_inspection WHERE inspection_date BETWEEN ? AND ? " +
            "AND COALESCE(TRIM(inspector), '') <> '' " +
            "AND COALESCE(LOWER(status), '') NOT IN ('cancelled', 'canceled', 'open slot') " +
            "AND (COALESCE(TRIM(customer_name), '') <> '' OR COALESCE(TRIM(first_name), '') <> '' " +
            "OR COALESCE(TRIM(last_name), '') <> '' OR COALESCE(TRIM(mac_id), '') <> '' " +
            "OR COALESCE(TRIM(address), '') <> '') " +
            "ORDER BY LOWER(TRIM(inspector)), inspection_date, COALESCE(row_num, 2147483647), inspection_time, id",
        weekStart, weekEnd);
    List<Map<String, Object>> jobs = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> job = new LinkedHashMap<>();
      String status = value(row.get("field_status")).trim().toLowerCase(Locale.ROOT);
      if (!StringUtils.hasText(status)) status = "fixed";
      job.put("id", row.get("id"));
      job.put("inspector", value(row.get("inspector")).trim());
      job.put("macId", value(row.get("mac_id")));
      job.put("customerName", customerName(value(row.get("customer_name")), value(row.get("first_name")), value(row.get("last_name"))));
      job.put("address", value(row.get("address")));
      job.put("suburb", value(row.get("suburb")));
      job.put("date", dateValue(row.get("inspection_date")));
      job.put("start", normalizeTime(value(row.get("inspection_time"))));
      job.put("projects", value(row.get("projects")));
      job.put("schedulerRemarks", value(row.get("scheduler_remarks")));
      job.put("inspectorRemark", value(row.get("inspector_remark")));
      job.put("fieldStatus", status);
      job.put("confirmed", number(row.get("manager_confirmed")) == 1);
      job.put("confirmedBy", value(row.get("manager_confirmed_by")));
      job.put("confirmedAt", row.get("manager_confirmed_at"));
      jobs.add(job);
    }
    Map<Long, List<Map<String, Object>>> photosByInspection = loadRoutePhotos(jobs);
    for (Map<String, Object> job : jobs) {
      long inspectionId = number(job.get("id"));
      job.put("photoSlots", photoSlots(photosByInspection.getOrDefault(inspectionId, List.of()), value(job.get("projects"))));
    }
    Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
    for (Map<String, Object> job : jobs) {
      grouped.computeIfAbsent(value(job.get("inspector")), ignored -> new ArrayList<>()).add(job);
    }
    List<Map<String, Object>> inspectors = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
      Map<String, Object> inspector = new LinkedHashMap<>();
      inspector.put("name", entry.getKey());
      inspector.put("jobs", entry.getValue());
      inspector.put("jobCount", entry.getValue().size());
      inspector.put("confirmedCount", entry.getValue().stream().filter(job -> Boolean.TRUE.equals(job.get("confirmed"))).count());
      inspectors.add(inspector);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("weekStart", weekStart.toString());
    response.put("weekEnd", weekEnd.toString());
    response.put("jobCount", jobs.size());
    response.put("doneCount", jobs.stream().filter(job -> "done".equals(job.get("fieldStatus"))).count());
    response.put("unavailableCount", jobs.stream().filter(job -> "customer_unavailable".equals(job.get("fieldStatus"))).count());
    response.put("pendingCount", jobs.stream().filter(job -> !isFinalFieldStatus(value(job.get("fieldStatus")))).count());
    response.put("confirmedCount", jobs.stream().filter(job -> Boolean.TRUE.equals(job.get("confirmed"))).count());
    response.put("inspectors", inspectors);
    return response;
  }

  @Transactional
  public void confirmInspection(CurrentUser user, long inspectionId, boolean confirmed) {
    requireManager(user);
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT field_status FROM schedule_inspection WHERE id = ?", inspectionId);
    if (rows.isEmpty()) throw new BadRequestException("Inspection was not found.");
    String status = value(rows.get(0).get("field_status")).trim().toLowerCase(Locale.ROOT);
    if (confirmed && !isFinalFieldStatus(status)) {
      throw new BadRequestException("Only Done or Customer unavailable inspections can be confirmed.");
    }
    String reviewer = value(user.getRealName()).trim();
    if (!StringUtils.hasText(reviewer)) reviewer = value(user.getUsername()).trim();
    int updated = confirmed
        ? jdbcTemplate.update(
            "UPDATE schedule_inspection SET manager_confirmed = 1, manager_confirmed_by = ?, manager_confirmed_at = NOW(), updated_at = NOW() WHERE id = ?",
            reviewer, inspectionId)
        : jdbcTemplate.update(
            "UPDATE schedule_inspection SET manager_confirmed = 0, manager_confirmed_by = NULL, manager_confirmed_at = NULL, updated_at = NOW() WHERE id = ?",
            inspectionId);
    if (updated != 1) throw new BadRequestException("Inspection was not found.");
  }

  private boolean isFinalFieldStatus(String status) {
    return "done".equals(status) || "customer_unavailable".equals(status);
  }

  private String dateValue(Object value) {
    if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate().toString();
    if (value instanceof LocalDate) return value.toString();
    String text = value(value);
    return text.length() >= 10 ? text.substring(0, 10) : text;
  }

  private List<Map<String, Object>> loadStops(LocalDate date, String inspectorName) {
    List<Map<String, Object>> stops = jdbcTemplate.query(
        "SELECT id, mac_id, customer_name, first_name, last_name, phone_number, address, suburb, city_council, " +
            "inspection_date, inspection_time, projects, status, scheduler_remarks, field_status, customer_email, inspector_remark " +
            "FROM schedule_inspection WHERE inspection_date = ? AND LOWER(TRIM(inspector)) = LOWER(TRIM(?)) " +
            "AND COALESCE(LOWER(status), '') NOT IN ('cancelled', 'canceled', 'open slot') " +
            "AND (COALESCE(TRIM(customer_name), '') <> '' OR COALESCE(TRIM(first_name), '') <> '' " +
            "OR COALESCE(TRIM(last_name), '') <> '' OR COALESCE(TRIM(mac_id), '') <> '' " +
            "OR COALESCE(TRIM(phone_number), '') <> '' OR COALESCE(TRIM(address), '') <> '') " +
            "ORDER BY COALESCE(row_num, 2147483647), inspection_time, id",
        (rs, rowNum) -> {
          Map<String, Object> stop = new LinkedHashMap<>();
          long id = rs.getLong("id");
          String projects = value(rs.getString("projects"));
          String start = normalizeTime(rs.getString("inspection_time"));
          int duration = durationMinutes(projects);
          stop.put("id", id);
          stop.put("macId", value(rs.getString("mac_id")));
          stop.put("customerName", customerName(rs.getString("customer_name"), rs.getString("first_name"), rs.getString("last_name")));
          stop.put("phoneNumber", value(rs.getString("phone_number")));
          stop.put("address", value(rs.getString("address")));
          stop.put("suburb", value(rs.getString("suburb")));
          stop.put("cityCouncil", value(rs.getString("city_council")));
          stop.put("date", rs.getDate("inspection_date").toLocalDate().toString());
          stop.put("start", start);
          stop.put("end", addMinutes(start, duration));
          stop.put("durationMinutes", duration);
          stop.put("projects", projects);
          stop.put("sourceStatus", value(rs.getString("status")));
          stop.put("fieldStatus", StringUtils.hasText(rs.getString("field_status")) ? rs.getString("field_status") : "fixed");
          stop.put("schedulerRemarks", value(rs.getString("scheduler_remarks")));
          stop.put("customerEmail", value(rs.getString("customer_email")));
          stop.put("inspectorRemark", value(rs.getString("inspector_remark")));
          return stop;
        }, date, inspectorName);

    // 一次读取当前路线全部照片，避免每个 stop 单独查询远程 MySQL。
    Map<Long, List<Map<String, Object>>> photosByInspection = loadRoutePhotos(stops);
    for (Map<String, Object> stop : stops) {
      long inspectionId = number(stop.get("id"));
      stop.put("photoSlots", photoSlots(
          photosByInspection.getOrDefault(inspectionId, List.of()), value(stop.get("projects"))));
    }

    return stops;
  }

  public void updateStatus(CurrentUser user, long inspectionId, String status, String reason) {
    requireOwnedInspection(user, inspectionId);
    if (!Arrays.asList("fixed", "done", "customer_unavailable").contains(status)) {
      throw new BadRequestException("Unsupported field status.");
    }
    if ("customer_unavailable".equals(status)) {
      String unavailableReason = value(reason).trim();
      if (!StringUtils.hasText(unavailableReason)) {
        throw new BadRequestException("Enter the reason the customer is unavailable.");
      }
      if (unavailableReason.length() > 1000) {
        throw new BadRequestException("The unavailable reason must be 1000 characters or fewer.");
      }
      jdbcTemplate.update(
          "UPDATE schedule_inspection SET field_status = ?, inspector_remark = ?, manager_confirmed = 0, manager_confirmed_by = NULL, manager_confirmed_at = NULL, updated_at = NOW() WHERE id = ?",
          status, unavailableReason, inspectionId);
      return;
    }
    if ("done".equals(status)) {
      requireCompletionPhotos(inspectionId);
    }
    jdbcTemplate.update("UPDATE schedule_inspection SET field_status = ?, manager_confirmed = 0, manager_confirmed_by = NULL, manager_confirmed_at = NULL, updated_at = NOW() WHERE id = ?", status, inspectionId);
  }

  /**
   * 将任务标记为完成前按产品检查必需媒体。
   * 空调沿用九张现场照片；电池/太阳能要求位置、平面图、测量资料和室内/室外视频。
   * 该校验位于后端，避免用户绕过前端直接调用接口完成任务。
   */
  private void requireCompletionPhotos(long inspectionId) {
    List<String> uploadedKeys = jdbcTemplate.queryForList(
        "SELECT DISTINCT category_key FROM inspection_photo WHERE inspection_id = ?",
        String.class, inspectionId);
    String projects = jdbcTemplate.queryForObject(
        "SELECT COALESCE(projects, '') FROM schedule_inspection WHERE id = ?",
        String.class, inspectionId);
    String normalizedProjects = value(projects).toLowerCase(Locale.ROOT);
    boolean batterySolar = hasProject(normalizedProjects, "battery")
        || hasProject(normalizedProjects, "sp") || normalizedProjects.contains("solar");
    boolean heatPump = hasProject(normalizedProjects, "hp") || normalizedProjects.contains("heat pump");
    boolean airConditioning = hasProject(normalizedProjects, "mac") || hasProject(normalizedProjects, "dac")
        || (!batterySolar && !heatPump);
    List<String> missingLabels = new ArrayList<>();
    if (airConditioning) {
      for (String[] required : REQUIRED_COMPLETION_PHOTOS) {
        if (!uploadedKeys.contains(required[0])) missingLabels.add(required[1]);
      }
    }
    if (batterySolar) {
      if (!uploadedKeys.contains("battery_serial_label") && !uploadedKeys.contains("battery_location")) {
        missingLabels.add("Battery location photo (indoor or outdoor)");
      }
      if (!uploadedKeys.contains("drawn_floor_plan_measurements")) {
        missingLabels.add("Floor Plan");
      }
      if (!uploadedKeys.contains("battery_measurements")) {
        missingLabels.add("Measurements");
      }
      if (!uploadedKeys.contains("battery_indoor_outdoor_video")) {
        missingLabels.add("Indoor / Outdoor Area Video");
      }
    }
    if (!missingLabels.isEmpty()) {
      throw new BadRequestException(
          "Upload all required inspection media before marking the job done. Missing: "
              + String.join(", ", missingLabels) + ".");
    }
  }

  private boolean hasProject(String projects, String token) {
    return Arrays.stream(value(projects).split("[^a-z0-9]+"))
        .anyMatch(part -> token.equals(part));
  }

  public void saveDetails(CurrentUser user, long inspectionId, String email, String remark) {
    requireOwnedInspection(user, inspectionId);
    if (email != null && email.length() > 240) throw new BadRequestException("Email is too long.");
    if (remark != null && remark.length() > 5000) throw new BadRequestException("Remark is too long.");
    jdbcTemplate.update(
        "UPDATE schedule_inspection SET customer_email = ?, inspector_remark = ?, manager_confirmed = 0, manager_confirmed_by = NULL, manager_confirmed_at = NULL, updated_at = NOW() WHERE id = ?",
        value(email), value(remark), inspectionId);
  }

  public Map<String, Object> shifts(CurrentUser user) {
    requireInspectorName(user);
    LocalDate today = LocalDate.now();
    LocalDate monday = today.with(DayOfWeek.MONDAY).plusWeeks(1);
    List<Map<String, Object>> days = new ArrayList<>();
    for (int offset = 0; offset < 7; offset++) {
      LocalDate date = monday.plusDays(offset);
      List<Map<String, Object>> found = jdbcTemplate.queryForList(
          "SELECT working, start_time, end_time, locked, submitted_at FROM inspector_shift WHERE user_id = ? AND shift_date = ?",
          user.getId(), date);
      Map<String, Object> day = new LinkedHashMap<>();
      day.put("date", date.toString());
      day.put("working", found.isEmpty() ? date.getDayOfWeek().getValue() <= 5 : number(found.get(0).get("working")) == 1);
      day.put("start", found.isEmpty() ? "09:00" : value(found.get(0).get("start_time")));
      day.put("end", found.isEmpty() ? "16:30" : value(found.get(0).get("end_time")));
      day.put("locked", !found.isEmpty() && number(found.get(0).get("locked")) == 1);
      day.put("submittedAt", found.isEmpty() ? null : found.get(0).get("submitted_at"));
      days.add(day);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("weekStart", monday.toString());
    response.put("days", days);
    return response;
  }

  public void submitShift(CurrentUser user, LocalDate date, boolean working, String start, String end) {
    requireInspectorName(user);
    LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY).plusWeeks(1);
    if (date.isBefore(monday) || date.isAfter(monday.plusDays(6))) {
      throw new BadRequestException("Only next week's availability can be submitted.");
    }
    start = normalizeTime(start);
    end = normalizeTime(end);
    if (working && (toMinutes(start) < 540 || toMinutes(end) > 990 || toMinutes(start) > toMinutes(end))) {
      throw new BadRequestException("Working time must be between 09:00 and 16:30.");
    }
    Integer existing = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM inspector_shift WHERE user_id = ? AND shift_date = ? AND locked = 1",
        Integer.class, user.getId(), date);
    if (existing != null && existing > 0) {
      throw new BadRequestException("This shift was already submitted. Ask a manager to adjust it.");
    }
    jdbcTemplate.update(
        "INSERT INTO inspector_shift(user_id, inspector_name, shift_date, working, start_time, end_time, locked, submitted_at) " +
            "VALUES(?, ?, ?, ?, ?, ?, 1, NOW()) ON DUPLICATE KEY UPDATE inspector_name=VALUES(inspector_name), " +
            "working=VALUES(working), start_time=VALUES(start_time), end_time=VALUES(end_time), locked=1, submitted_at=NOW()",
        user.getId(), user.getRealName(), date, working ? 1 : 0, start, end);
  }

  public Map<String, Object> managerShifts(CurrentUser user, LocalDate start) {
    requireManager(user);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("weekStart", start.toString());
    response.put("shifts", jdbcTemplate.queryForList(
        "SELECT inspector_name, shift_date, working, start_time, end_time, locked, submitted_at " +
            "FROM inspector_shift WHERE shift_date BETWEEN ? AND ? ORDER BY inspector_name, shift_date",
        start, start.plusDays(6)));
    return response;
  }

  public void managerSaveShift(CurrentUser user, String inspectorName, LocalDate date,
                               boolean working, String start, String end) {
    requireManager(user);
    List<Map<String, Object>> accounts = jdbcTemplate.queryForList(
        "SELECT id, real_name FROM sys_user WHERE role_code IN ('scheduler', 'inspector') AND status = 1 " +
            "AND LOWER(TRIM(real_name)) = LOWER(TRIM(?)) LIMIT 1", inspectorName);
    if (accounts.isEmpty()) {
      throw new BadRequestException("Create an enabled Scheduler account with this Real name before setting its shift.");
    }
    long userId = number(accounts.get(0).get("id"));
    String realName = value(accounts.get(0).get("real_name"));
    start = normalizeTime(start);
    end = normalizeTime(end);
    if (working && (toMinutes(start) < 540 || toMinutes(end) > 990 || toMinutes(start) > toMinutes(end))) {
      throw new BadRequestException("Working time must be between 09:00 and 16:30.");
    }
    jdbcTemplate.update(
        "INSERT INTO inspector_shift(user_id, inspector_name, shift_date, working, start_time, end_time, locked, submitted_at) " +
            "VALUES(?, ?, ?, ?, ?, ?, 1, NOW()) ON DUPLICATE KEY UPDATE inspector_name=VALUES(inspector_name), " +
            "working=VALUES(working), start_time=VALUES(start_time), end_time=VALUES(end_time), locked=1, submitted_at=NOW()",
        userId, realName, date, working ? 1 : 0, start, end);
  }

  @Transactional
  public Map<String, Object> upload(CurrentUser user, long inspectionId, String categoryKey, MultipartFile file) throws IOException {
    requireInspectionMediaAccess(user, inspectionId);
    String[] slot = PHOTO_SLOTS.stream().filter(item -> item[0].equals(categoryKey)).findFirst()
        .orElseThrow(() -> new BadRequestException("Unknown photo category."));
    boolean videoSlot = value(slot[2]).contains("video");
    if (file == null || file.isEmpty()) throw new BadRequestException(videoSlot ? "Choose a video first." : "Choose a photo first.");
    long maximumBytes = videoSlot ? 3L * 1024 * 1024 * 1024 : 20L * 1024 * 1024;
    if (file.getSize() > maximumBytes) {
      throw new BadRequestException(videoSlot ? "Video must be 3 GB or smaller." : "Photo must be 20MB or smaller.");
    }
    String contentType = value(file.getContentType());
    String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
    if (videoSlot && !SAFE_VIDEO_TYPES.contains(normalizedContentType)) {
      throw new BadRequestException("Only MP4, MOV, WEBM, M4V or 3GP video files are accepted.");
    }
    if (!videoSlot && !SAFE_IMAGE_TYPES.contains(normalizedContentType)) {
      throw new BadRequestException("Only JPG, PNG, GIF, WEBP or HEIC image files are accepted.");
    }
    String extension = safeExtension(file.getOriginalFilename(), normalizedContentType, videoSlot);
    String storedName = UUID.randomUUID() + extension;
    Path folder = uploadRoot.resolve(String.valueOf(inspectionId)).normalize();
    if (!folder.startsWith(uploadRoot)) throw new BadRequestException("Invalid upload path.");
    Files.createDirectories(folder);
    Path storedPath = folder.resolve(storedName);
    Files.copy(file.getInputStream(), storedPath, StandardCopyOption.REPLACE_EXISTING);
    Long id;
    try {
      jdbcTemplate.update(
          "INSERT INTO inspection_photo(inspection_id, category_key, category_label, original_name, stored_name, content_type, file_size, uploaded_by) VALUES(?,?,?,?,?,?,?,?)",
          inspectionId, categoryKey, slot[1], value(file.getOriginalFilename()), storedName, contentType, file.getSize(), user.getId());
      // 事务保证 LAST_INSERT_ID 与 INSERT 使用同一个数据库连接。
      id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
      clearManagerConfirmation(inspectionId);
    } catch (RuntimeException ex) {
      Files.deleteIfExists(storedPath);
      throw ex;
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("url", "/api/inspector/photos/" + id);
    return result;
  }

  public PhotoDownload photo(CurrentUser user, long photoId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT p.inspection_id, p.stored_name, p.original_name, p.content_type FROM inspection_photo p WHERE p.id = ?", photoId);
    if (rows.isEmpty()) throw new BadRequestException("Photo was not found.");
    long inspectionId = number(rows.get(0).get("inspection_id"));
    if (!isManager(user)) requireOwnedInspection(user, inspectionId);
    Path file = uploadRoot.resolve(String.valueOf(inspectionId)).resolve(value(rows.get(0).get("stored_name"))).normalize();
    if (!file.startsWith(uploadRoot) || !Files.isRegularFile(file)) throw new BadRequestException("Photo file was not found.");
    return new PhotoDownload(new FileSystemResource(file), value(rows.get(0).get("content_type")), value(rows.get(0).get("original_name")));
  }

  @Transactional
  public void deletePhoto(CurrentUser user, long photoId) throws IOException {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT inspection_id, stored_name FROM inspection_photo WHERE id = ?", photoId);
    if (rows.isEmpty()) throw new BadRequestException("Uploaded file was not found.");
    Map<String, Object> row = rows.get(0);
    long inspectionId = number(row.get("inspection_id"));
    requireInspectionMediaAccess(user, inspectionId);
    Path file = uploadRoot.resolve(String.valueOf(inspectionId)).resolve(value(row.get("stored_name"))).normalize();
    if (!file.startsWith(uploadRoot)) throw new BadRequestException("Invalid upload path.");
    // 以当前检查任务归属为权限依据；历史数据可能由旧账号 ID 上传，不能因账号迁移阻止删除。
    int deleted = jdbcTemplate.update("DELETE FROM inspection_photo WHERE id = ?", photoId);
    if (deleted != 1) throw new BadRequestException("Uploaded file was not found.");
    clearManagerConfirmation(inspectionId);
    // 旧版本上传文件可能属于不同 Linux 账号；物理文件删除失败不应阻止检查员移除数据库记录。
    try {
      Files.deleteIfExists(file);
    } catch (IOException | SecurityException ex) {
      LOG.warn("Uploaded file record deleted but legacy file cleanup failed photoId={} path={} reason={}",
          photoId, file, ex.getMessage());
    }
  }

  /** 将备注直接绑定到单张照片，不需要额外上传备注文件。 */
  @Transactional
  public void updatePhotoRemark(CurrentUser user, long photoId, String remark) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT inspection_id FROM inspection_photo WHERE id = ?", photoId);
    if (rows.isEmpty()) throw new BadRequestException("Uploaded photo was not found.");
    long inspectionId = number(rows.get(0).get("inspection_id"));
    requireInspectionMediaAccess(user, inspectionId);
    String cleaned = value(remark).trim();
    if (cleaned.length() > 2000) throw new BadRequestException("Photo remark must be 2000 characters or fewer.");
    int updated = jdbcTemplate.update("UPDATE inspection_photo SET remark = ? WHERE id = ?", cleaned, photoId);
    if (updated != 1) throw new BadRequestException("Uploaded photo was not found.");
  }

  private Map<Long, List<Map<String, Object>>> loadRoutePhotos(List<Map<String, Object>> stops) {
    Map<Long, List<Map<String, Object>>> grouped = new HashMap<>();
    if (stops.isEmpty()) return grouped;
    String placeholders = String.join(",", java.util.Collections.nCopies(stops.size(), "?"));
    Object[] ids = stops.stream().map(stop -> stop.get("id")).toArray();
    List<Map<String, Object>> files = jdbcTemplate.queryForList(
        "SELECT id, inspection_id, category_key, original_name, remark, created_at FROM inspection_photo " +
            "WHERE inspection_id IN (" + placeholders + ") ORDER BY id", ids);
    for (Map<String, Object> file : files) {
      grouped.computeIfAbsent(number(file.get("inspection_id")), ignored -> new ArrayList<>()).add(file);
    }
    return grouped;
  }

  /** New field evidence invalidates an earlier weekly sign-off until a manager reviews it again. */
  private void clearManagerConfirmation(long inspectionId) {
    jdbcTemplate.update(
        "UPDATE schedule_inspection SET manager_confirmed = 0, manager_confirmed_by = NULL, manager_confirmed_at = NULL, updated_at = NOW() WHERE id = ?",
        inspectionId);
  }

  private List<Map<String, Object>> photoSlots(List<Map<String, Object>> files, String projects) {
    List<Map<String, Object>> slots = new ArrayList<>();
    for (String[] definition : PHOTO_SLOTS) {
      String productFilter = definition.length > 3 ? value(definition[3]).toLowerCase(Locale.ROOT) : "";
      if (StringUtils.hasText(productFilter)
          && !value(projects).toLowerCase(Locale.ROOT).contains(productFilter)) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("key", definition[0]);
      item.put("label", definition[1]);
      item.put("group", definition.length > 2 ? definition[2] : "standard");
      List<Map<String, Object>> matches = new ArrayList<>();
      for (Map<String, Object> file : files) {
        if (definition[0].equals(value(file.get("category_key")))) {
          Map<String, Object> photo = new LinkedHashMap<>();
          photo.put("id", file.get("id"));
          photo.put("name", file.get("original_name"));
          photo.put("url", "/api/inspector/photos/" + file.get("id"));
          photo.put("remark", value(file.get("remark")));
          matches.add(photo);
        }
      }
      item.put("files", matches);
      slots.add(item);
    }
    return slots;
  }

  private void requireOwnedInspection(CurrentUser user, long inspectionId) {
    requireInspectorName(user);
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM schedule_inspection WHERE id = ? AND LOWER(TRIM(inspector)) = LOWER(TRIM(?))",
        Integer.class, inspectionId, assignedInspectorName(user));
    if (count == null || count < 1) throw new BadRequestException("This inspection is not assigned to the current inspector.");
  }

  /** Inspectors may edit their assigned media; managers and administrators may edit any reviewed job. */
  private void requireInspectionMediaAccess(CurrentUser user, long inspectionId) {
    if (!isManager(user)) {
      requireOwnedInspection(user, inspectionId);
      return;
    }
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM schedule_inspection WHERE id = ?", Integer.class, inspectionId);
    if (count == null || count < 1) throw new BadRequestException("Inspection was not found.");
  }

  private String assignedInspectorName(CurrentUser user) {
    String accountName = value(user == null ? null : user.getRealName()).trim();
    // 账号 Real name 按用户管理页面保存；这里映射 Google Sheet 中使用的完整检查员名称。
    if ("jeff".equalsIgnoreCase(accountName)) return "Jeff Li";
    if ("kyle".equalsIgnoreCase(accountName)) return "Kyle NSW";
    return accountName;
  }

  private void requireInspectorName(CurrentUser user) {
    if (user == null || !StringUtils.hasText(user.getRealName())) {
      throw new BadRequestException("The account has no Inspector real name. Ask a manager to update it.");
    }
  }

  private void requireManager(CurrentUser user) {
    if (!isManager(user)) {
      throw new BadRequestException("Manager access is required.");
    }
  }

  private boolean isManager(CurrentUser user) {
    String role = user == null ? "" : value(user.getRoleCode()).toLowerCase(Locale.ROOT);
    return "admin".equals(role) || "manager".equals(role);
  }

  private int durationMinutes(String projects) {
    String text = value(projects).toLowerCase(Locale.ROOT);
    int count = 0;
    for (String token : Arrays.asList("mac", "dac", "battery", "hp", "sp")) if (text.contains(token)) count++;
    return count <= 1 ? 30 : count == 2 ? 45 : 60;
  }

  private String customerName(String customerName, String firstName, String lastName) {
    if (StringUtils.hasText(customerName)) return customerName.trim();
    return (value(firstName) + " " + value(lastName)).trim();
  }

  private String normalizeTime(String text) {
    String value = value(text).trim();
    if (value.matches("\\d{1,2}:\\d{2}")) {
      String[] parts = value.split(":");
      return String.format("%02d:%02d", Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
    try {
      return LocalDateTime.parse("2000-01-01 " + value, DateTimeFormatter.ofPattern("yyyy-MM-dd h:mm a", Locale.ENGLISH))
          .format(DateTimeFormatter.ofPattern("HH:mm"));
    } catch (Exception ignored) {
      return "09:00";
    }
  }

  private String addMinutes(String time, int duration) {
    int total = toMinutes(time) + duration;
    return String.format("%02d:%02d", (total / 60) % 24, total % 60);
  }

  private int toMinutes(String time) {
    String[] parts = normalizeTime(time).split(":");
    return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
  }

  String safeExtension(String name, String contentType, boolean videoSlot) {
    String source = value(name);
    int dot = source.lastIndexOf('.');
    String extension = dot >= 0 ? source.substring(dot).toLowerCase(Locale.ROOT) : ".jpg";
    if (videoSlot && extension.matches("\\.(mp4|mov|webm|m4v|3gp)")) {
      return extension;
    }
    if (!videoSlot && extension.matches("\\.(jpg|jpeg|png|gif|webp|heic|heif)")) {
      return extension;
    }
    String normalizedType = value(contentType).toLowerCase(Locale.ROOT);
    if (videoSlot) {
      if ("video/quicktime".equals(normalizedType)) return ".mov";
      if ("video/webm".equals(normalizedType)) return ".webm";
      if ("video/x-m4v".equals(normalizedType)) return ".m4v";
      if ("video/3gpp".equals(normalizedType)) return ".3gp";
      return ".mp4";
    }
    if ("image/png".equals(normalizedType)) return ".png";
    if ("image/gif".equals(normalizedType)) return ".gif";
    if ("image/webp".equals(normalizedType)) return ".webp";
    if ("image/heic".equals(normalizedType)) return ".heic";
    if ("image/heif".equals(normalizedType)) return ".heif";
    return ".jpg";
  }

  private String value(Object value) { return value == null ? "" : String.valueOf(value); }
  private long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value(value)); }

  public static class PhotoDownload {
    public final Resource resource;
    public final String contentType;
    public final String originalName;
    public PhotoDownload(Resource resource, String contentType, String originalName) {
      this.resource = resource;
      this.contentType = contentType;
      this.originalName = originalName;
    }
  }
}
