package com.smartuser.schedule.service;

import com.smartuser.schedule.config.GoogleSheetProperties;
import com.smartuser.schedule.model.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Admin/Manager 登录后自动发现并导入尚未进入数据库的新 Week Sheet。 */
@Service
public class GoogleSheetAutoImportService {
  private static final Logger LOG = LoggerFactory.getLogger(GoogleSheetAutoImportService.class);
  private static final Pattern WEEK_PATTERN = Pattern.compile("(?i)^Week\\s+(\\d+).*");
  private static final LocalDate WEEK_388_START = LocalDate.of(2026, 7, 27);

  private final GoogleSheetProperties properties;
  private final GoogleSheetSyncService googleSheetSyncService;
  private final InspectionService inspectionService;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public GoogleSheetAutoImportService(GoogleSheetProperties properties,
                                      GoogleSheetSyncService googleSheetSyncService,
                                      InspectionService inspectionService) {
    this.properties = properties;
    this.googleSheetSyncService = googleSheetSyncService;
    this.inspectionService = inspectionService;
  }

  @Async("googleSheetAutoSyncExecutor")
  public void importNewWeeksAfterLogin(CurrentUser currentUser) {
    if (!mayAutoImport(currentUser) || !running.compareAndSet(false, true)) {
      return;
    }
    try {
      List<SheetWeek> candidates = new ArrayList<SheetWeek>();
      LocalDate today = LocalDate.now();
      for (Map<String, Object> sheet : googleSheetSyncService.listImportSheets()) {
        SheetWeek week = sheetWeek(sheet);
        if (week != null && !week.end.isBefore(today)) {
          candidates.add(week);
        }
      }
      candidates.sort(Comparator.comparing(item -> item.start));

      int imported = 0;
      int refreshed = 0;
      int processed = 0;
      int limit = Math.max(1, properties.getAutoImportMaxWeeks());
      for (SheetWeek week : candidates) {
        if (processed >= limit) {
          break;
        }
        processed++;
        if (inspectionService.hasImportedGoogleSheetWeek(week.gid, week.name)) {
          try {
            // 已导入周使用安全合并：只更新 Open Slot 并补充新行，不删除本地状态、照片、备注或 Book 预约。
            inspectionService.mergeGoogleSheetWeek(week.gid, currentUser);
            refreshed++;
            LOG.info("Safely refreshed existing Google Sheet tab after {} login sheet={} gid={}",
                currentUser.getRoleCode(), week.name, week.gid);
          } catch (Exception ex) {
            LOG.error("Automatic Google Sheet merge failed sheet={} gid={} reason={}",
                week.name, week.gid, ex.getMessage(), ex);
          }
          continue;
        }
        try {
          // 新周使用 replaceExisting=false；若数据库已经有该周数据则安全失败，不会覆盖本地预约或巡检状态。
          inspectionService.importGoogleSheetWeek(week.gid, false, currentUser);
          imported++;
          LOG.info("Automatically imported new Google Sheet tab after {} login sheet={} gid={}",
              currentUser.getRoleCode(), week.name, week.gid);
        } catch (Exception ex) {
          // 单个周失败不能阻止其他新周；该周没有成功批次，下次 Admin/Manager 登录会自动重试。
          LOG.error("Automatic Google Sheet import failed sheet={} gid={} reason={}",
              week.name, week.gid, ex.getMessage(), ex);
        }
      }
      LOG.info("Automatic Google Sheet login check completed candidates={} imported={} refreshed={} user={}",
          candidates.size(), imported, refreshed, currentUser.getUsername());
    } catch (Exception ex) {
      // Google 暂时不可用不能影响登录；管理员仍可进入系统并稍后再次登录触发重试。
      LOG.error("Automatic Google Sheet login check failed user={} reason={}",
          currentUser.getUsername(), ex.getMessage(), ex);
    } finally {
      running.set(false);
    }
  }

  boolean mayAutoImport(CurrentUser user) {
    // enabled 是旧的“写回同步”开关；当前 Sheet 只读，因此自动读取不能依赖该旧开关。
    if (!properties.isAutoImportOnLogin() || user == null
        || !StringUtils.hasText(user.getRoleCode())) {
      return false;
    }
    String role = user.getRoleCode().trim().toLowerCase(Locale.ROOT);
    return "admin".equals(role) || "manager".equals(role);
  }

  private SheetWeek sheetWeek(Map<String, Object> sheet) {
    if (sheet == null || sheet.get("gid") == null || sheet.get("name") == null) {
      return null;
    }
    String name = String.valueOf(sheet.get("name")).trim();
    Matcher matcher = WEEK_PATTERN.matcher(name);
    if (!matcher.matches()) {
      return null;
    }
    try {
      long gid = Long.parseLong(String.valueOf(sheet.get("gid")));
      int number = Integer.parseInt(matcher.group(1));
      LocalDate start = WEEK_388_START.plusWeeks(number - 388L);
      return new SheetWeek(gid, name, start, start.plusDays(6));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static final class SheetWeek {
    private final long gid;
    private final String name;
    private final LocalDate start;
    private final LocalDate end;

    private SheetWeek(long gid, String name, LocalDate start, LocalDate end) {
      this.gid = gid;
      this.name = name;
      this.start = start;
      this.end = end;
    }
  }
}
