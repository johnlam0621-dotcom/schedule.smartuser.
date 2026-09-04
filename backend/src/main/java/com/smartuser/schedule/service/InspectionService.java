package com.smartuser.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.mapper.ImportBatchMapper;
import com.smartuser.schedule.mapper.InspectionMapper;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.model.GoogleSheetSyncResult;
import com.smartuser.schedule.model.ImportBatch;
import com.smartuser.schedule.model.ImportResult;
import com.smartuser.schedule.model.InspectionRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 巡检导入、列表、路线地图核心业务服务。
 *
 * 功能作用：
 * 1. 解析 Inspection Import 上传的 CSV/Excel 文件，转换为 schedule_inspection 单表数据。
 * 2. 负责导入批次记录、分页查询、条件删除、批量删除、在线编辑、取消、Excel 导出。
 * 3. 为 Routes map 页面提供路线选项、汇总卡片、分页列表和导出数据。
 * 4. Google Sheet 只作为只读数据源；导入、预约、取消和改期均只写应用数据库。
 *
 * SQL 约定：
 * - 当前方法均围绕 schedule_inspection/schedule_import_batch 单表处理，使用 MyBatis-Plus Wrapper/BaseMapper。
 * - 如后续出现跨表统计或复杂关联查询，应按项目规则新增 MyBatis XML 映射承载多表 SQL。
 */
@Service
public class InspectionService {
  private static final Logger LOG = LoggerFactory.getLogger(InspectionService.class);
  private static final List<String> BOOK_PRODUCTS = Arrays.asList(
      "MAC", "MAC + DAC", "MAC + HP + Battery", "MAC + Battery", "MAC + HP",
      "MAC + DAC + Battery", "DAC", "DAC + Battery", "DAC + HP + Battery",
      "HP + Battery", "HP only", "Battery only", "ALL PRODUCTS",
      "SP + Battery + MAC", "SP + Battery");
  private static final List<String> BOOK_ATOMIC_PRODUCTS = Arrays.asList("MAC", "DAC", "Battery", "HP", "SP");
  private static final List<String> AUSTRALIAN_STATES = Arrays.asList("NSW", "VIC", "QLD", "SA", "WA", "TAS", "ACT", "NT");
  private static final int SINGLE_PRODUCT_DURATION_MINUTES = 30;
  private static final int DOUBLE_PRODUCT_DURATION_MINUTES = 45;
  private static final int DEFAULT_BOOK_DURATION_MINUTES = 60;
  private static final int MAX_ROUTE_DETOUR_MINUTES = 15;
  private static final double MAX_ROUTE_DETOUR_RATIO = 1.35d;
  private static final List<InspectorProfile> DEFAULT_BOOK_INSPECTORS = Arrays.asList(
      new InspectorProfile("Tarun", "West", "Williamstown VIC 3016", "09:00", "16:30"),
      new InspectorProfile("Ronit", "West", "Tarneit VIC 3029", "09:00", "16:30"),
      new InspectorProfile("Jeff Li", "West", "Point Cook VIC 3030", "09:00", "16:30"),
      new InspectorProfile("Dylan", "East", "Bayswater VIC 3153", "09:00", "16:30"),
      new InspectorProfile("Vishesh", "South East", "Springvale VIC 3171", "09:00", "16:30"),
      new InspectorProfile("Rohien", "South East", "Glen Waverley VIC 3150", "09:00", "16:30"),
      new InspectorProfile("Lakshay", "South East", "Springvale VIC 3171", "09:00", "16:30"),
      new InspectorProfile("Andreas", "South East", "Clayton VIC 3168", "09:00", "16:30"),
      new InspectorProfile("Kyle NSW", "NSW", "Seven Hills NSW 2147", "09:00", "16:30"));

  private final InspectionMapper inspectionMapper;
  private final ImportBatchMapper importBatchMapper;
  private final ObjectMapper objectMapper;
  private final GoogleSheetSyncService googleSheetSyncService;
  private final TransactionTemplate transactionTemplate;

  public InspectionService(InspectionMapper inspectionMapper, ImportBatchMapper importBatchMapper, ObjectMapper objectMapper,
                           GoogleSheetSyncService googleSheetSyncService,
                           PlatformTransactionManager transactionManager) {
    this.inspectionMapper = inspectionMapper;
    this.importBatchMapper = importBatchMapper;
    this.objectMapper = objectMapper;
    this.googleSheetSyncService = googleSheetSyncService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public ImportResult importFile(MultipartFile file, CurrentUser currentUser) {
    // 导入总流程：校验文件 -> 解析数据 -> 创建批次 -> 写入记录 -> 同步 Google Sheet -> 回写批次统计。
    if (file == null || file.isEmpty()) {
      throw new BadRequestException("Upload file is required.");
    }
    String fileName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
    String lowerName = fileName.toLowerCase(Locale.ENGLISH);
    String detectedType = detectFileType(file);
    boolean excelContent = "xlsx".equals(detectedType) || "xls".equals(detectedType);
    ImportResult result = new ImportResult();
    result.setFileName(fileName);
    List<InspectionRecord> records;
    LOG.info("Starting inspection-file import fileName={} detectedType={} size={} user={}", fileName, detectedType,
        file.getSize(), currentUser == null ? "system" : currentUser.getUsername());
    try {
      // 根据真实文件头和扩展名选择解析方式，避免 CSV 文件被错误当作 Excel 处理。
      if (excelContent || lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
        records = parseWorkbook(file, result);
      } else if (lowerName.endsWith(".csv")) {
        records = parseCsv(file, result);
      } else {
        throw new BadRequestException("Only csv, xls and xlsx files are supported.");
      }
    } catch (BadRequestException ex) {
      LOG.error("Import-file business validation failed fileName={} error={}", fileName, ex.getMessage(), ex);
      throw ex;
    } catch (Exception ex) {
      LOG.error("Import-file parsing failed fileName={} error={}", fileName, ex.getMessage(), ex);
      throw new BadRequestException("Import failed: " + ex.getMessage());
    }
    LOG.info("Import-file parsing completed fileName={} scannedRows={} validRows={} failedRows={}", fileName,
        result.getTotalRows(), records.size(), result.getErrors().size());
    String fileType = excelContent ? detectedType : extension(fileName);
    Long batchId = createImportBatch(fileName, fileType, result.getTotalRows(), records.size(),
        result.getErrors().size(), currentUser);
    LOG.info("Import batch created batchId={} fileName={} fileType={}", batchId, fileName, fileType);
    for (InspectionRecord record : records) {
      record.setImportBatchId(batchId);
    }
    insertRecords(records);
    LOG.info("Imported records saved to the database batchId={} count={}", batchId, records.size());
    result.setBatchId(batchId);
    result.setSuccessRows(records.size());
    result.setFailedRows(result.getErrors().size());
    // CSV/Excel 只导入数据库，不能把数据库列格式追加到业务排班 Sheet。
    GoogleSheetSyncResult syncResult = GoogleSheetSyncResult.skipped(
        "Database import completed; operational Google Sheet was not modified.");
    result.setGoogleSheetStatus(syncResult.getStatus());
    result.setGoogleSheetRows(syncResult.getRows());
    result.setGoogleSheetMessage(syncResult.getMessage());
    updateImportBatch(batchId, result);
    LOG.info("Import process completed batchId={} successRows={} failedRows={}", batchId,
        result.getSuccessRows(), result.getFailedRows());
    return result;
  }

  public Map<String, Object> importGoogleSheetWeek(Long sheetGid, boolean replaceExisting, CurrentUser currentUser) {
    if (sheetGid == null) {
      throw new BadRequestException("Choose a Google Sheet tab.");
    }
    Map<String, Object> source = googleSheetSyncService.readImportSheet(sheetGid);
    String sheetName = String.valueOf(source.get("sheetName"));
    int weekNumber = parseWeekNumber(sheetName);
    // 业务周基准：Google Sheet 的 Week 388 从 2026-07-27（周一）开始。
    // 如果未来 Sheet 的周编号在新年度重新从 1 开始，必须同时更新这里和前端 RoutesMapView 的基准。
    LocalDate weekStart = LocalDate.of(2026, 7, 27).plusWeeks(weekNumber - 388L);
    LocalDate weekEnd = weekStart.plusDays(6);
    Object rawValues = source.get("values");
    if (!(rawValues instanceof List)) {
      throw new BadRequestException("The selected Google Sheet tab has no readable rows.");
    }

    ImportResult result = new ImportResult();
    result.setFileName("Google Sheet - " + sheetName);
    List<InspectionRecord> records = new ArrayList<InspectionRecord>();
    String currentDay = "";
    List<?> rows = (List<?>) rawValues;
    for (int index = 1; index < rows.size(); index++) {
      result.setTotalRows(result.getTotalRows() + 1);
      if (!(rows.get(index) instanceof List)) {
        continue;
      }
      List<?> row = (List<?>) rows.get(index);
      Map<String, String> values = googleSheetRow(row);
      boolean openSlot = isOpenSheetSlot(values);
      if (!isDataRow(values) && !openSlot) {
        currentDay = updateCurrentDay(values, currentDay);
        continue;
      }
      try {
        InspectionRecord record = toInspectionRecord(values, currentDay, index + 1);
        record.setInspectionDate(alignSheetDateToWeek(record.getInspectionDate(), weekStart, weekEnd));
        if (openSlot) {
          record.setStatus("Open Slot");
        }
        attachSheetSource(record, sheetGid, sheetName, index + 1);
        if (record.getInspectionDate() != null
            && !record.getInspectionDate().isBefore(weekStart)
            && !record.getInspectionDate().isAfter(weekEnd)) {
          records.add(record);
        }
      } catch (Exception ex) {
        result.getErrors().add("Row " + (index + 1) + ": " + ex.getMessage());
      }
    }
    if (records.isEmpty()) {
      throw new BadRequestException("No appointments dated inside " + weekStart + " to " + weekEnd + " were found.");
    }

    // 先在事务外读取远程 Sheet；只有删除旧周和批量写入时才占用数据库连接。
    Map<String, Object> persistence = transactionTemplate.execute(transactionStatus -> {
      LambdaQueryWrapper<InspectionRecord> weekFilter = new LambdaQueryWrapper<InspectionRecord>()
          .between(InspectionRecord::getInspectionDate, weekStart, weekEnd);
      List<InspectionRecord> existingWeekRecords = inspectionMapper.selectList(weekFilter);
      long existingRows = existingWeekRecords.size();
      List<InspectionRecord> applicationBookings = existingWeekRecords.stream()
          .filter(this::isApplicationBooking)
          .toList();
      // 新周可能在首次 Sheet 导入前已经通过 Book 页面建立预约。此类预约必须保留，同时允许自动导入其余空位。
      // 只有 CSV、手动或旧 Sheet 行等非 Book 数据已存在时才要求人工确认 Replace，避免静默覆盖业务数据。
      if (containsNonApplicationWeekRows(existingWeekRecords) && !replaceExisting) {
        throw new BadRequestException(sheetName + " already has " + existingRows
            + " database rows. Select Replace existing week to refresh it without duplicates.");
      }
      // Google Sheet 只作为只读排班来源。刷新一周时删除旧导入行，但保留应用 Book 页面创建的预约。
      List<Long> staleImportedIds = existingWeekRecords.stream()
          .filter(record -> !isApplicationBooking(record))
          .map(InspectionRecord::getId)
          .filter(Objects::nonNull)
          .toList();
      int removedRows = staleImportedIds.isEmpty() ? 0 : inspectionMapper.deleteBatchIds(staleImportedIds);
      Long batchId = createImportBatch(result.getFileName(), "google-sheet", result.getTotalRows(), records.size(),
          result.getErrors().size(), currentUser);
      for (InspectionRecord record : records) {
        record.setImportBatchId(batchId);
      }
      insertRecords(records);
      // Book 预约原本占用了一个 Sheet 空位。重新读取 Sheet 后移除同一日期、检查员和时间的空位，避免重复预约。
      removeReimportedSlotsConsumedByBookings(batchId, applicationBookings);
      result.setBatchId(batchId);
      result.setSuccessRows(records.size());
      result.setFailedRows(result.getErrors().size());
      result.setGoogleSheetStatus("skipped");
      result.setGoogleSheetRows(0);
      result.setGoogleSheetMessage("Read-only Sheet import; no rows were written back to Google.");
      updateImportBatch(batchId, result);
      Map<String, Object> saved = new LinkedHashMap<String, Object>();
      saved.put("removedRows", removedRows);
      saved.put("batchId", batchId);
      return saved;
    });
    if (persistence == null) {
      throw new BadRequestException("Google Sheet week import transaction did not complete.");
    }

    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("sheetName", sheetName);
    response.put("sheetGid", sheetGid);
    response.put("weekNumber", weekNumber);
    response.put("weekStart", weekStart);
    response.put("weekEnd", weekEnd);
    response.put("removedRows", persistence.get("removedRows"));
    response.put("importResult", result);
    return response;
  }

  public boolean hasImportedGoogleSheetWeek(String sheetName) {
    if (!StringUtils.hasText(sheetName)) {
      return false;
    }
    // 自动同步只识别已经成功创建的 Google Sheet 导入批次；失败的周会在下次登录时重试。
    Long imported = importBatchMapper.selectCount(new LambdaQueryWrapper<ImportBatch>()
        .eq(ImportBatch::getFileType, "google-sheet")
        .eq(ImportBatch::getFileName, "Google Sheet - " + sheetName.trim()));
    return imported != null && imported > 0;
  }

  public boolean hasImportedGoogleSheetWeek(Long sheetGid, String sheetName) {
    if (sheetGid != null && inspectionMapper != null) {
      // The tab title is editable, while gid is stable. Detect an existing import by
      // gid first so renaming "Week 393" does not make auto-import treat it as a new
      // week and then fail against the already populated database date range.
      Long linkedRows = inspectionMapper.selectCount(new LambdaQueryWrapper<InspectionRecord>()
          .like(InspectionRecord::getRawJson, "\"sourceSheetGid\":" + sheetGid));
      if (linkedRows != null && linkedRows > 0) {
        return true;
      }
    }
    return hasImportedGoogleSheetWeek(sheetName);
  }

  public Map<String, Object> mergeGoogleSheetWeek(Long sheetGid, CurrentUser currentUser) {
    if (sheetGid == null) {
      throw new BadRequestException("Choose a Google Sheet tab.");
    }
    Map<String, Object> source = googleSheetSyncService.readImportSheet(sheetGid);
    String sheetName = String.valueOf(source.get("sheetName"));
    int weekNumber = parseWeekNumber(sheetName);
    LocalDate weekStart = LocalDate.of(2026, 7, 27).plusWeeks(weekNumber - 388L);
    LocalDate weekEnd = weekStart.plusDays(6);
    Object rawValues = source.get("values");
    if (!(rawValues instanceof List)) {
      throw new BadRequestException("The selected Google Sheet tab has no readable rows.");
    }

    List<InspectionRecord> incomingRecords = new ArrayList<InspectionRecord>();
    List<?> rows = (List<?>) rawValues;
    String currentDay = "";
    int scannedRows = 0;
    for (int index = 1; index < rows.size(); index++) {
      scannedRows++;
      if (!(rows.get(index) instanceof List)) {
        continue;
      }
      Map<String, String> values = googleSheetRow((List<?>) rows.get(index));
      boolean openSlot = isOpenSheetSlot(values);
      if (!isDataRow(values) && !openSlot) {
        currentDay = updateCurrentDay(values, currentDay);
        continue;
      }
      try {
        InspectionRecord record = toInspectionRecord(values, currentDay, index + 1);
        record.setInspectionDate(alignSheetDateToWeek(record.getInspectionDate(), weekStart, weekEnd));
        if (openSlot) {
          record.setStatus("Open Slot");
        }
        attachSheetSource(record, sheetGid, sheetName, index + 1);
        if (record.getInspectionDate() != null
            && !record.getInspectionDate().isBefore(weekStart)
            && !record.getInspectionDate().isAfter(weekEnd)) {
          incomingRecords.add(record);
        }
      } catch (Exception ex) {
        LOG.warn("Skipping invalid Sheet row during safe merge sheet={} row={} reason={}",
            sheetName, index + 1, ex.getMessage());
      }
    }
    if (incomingRecords.isEmpty()) {
      throw new BadRequestException("No appointments dated inside " + weekStart + " to " + weekEnd + " were found.");
    }

    final int totalRows = scannedRows;
    Map<String, Object> merged = transactionTemplate.execute(status -> {
      List<InspectionRecord> existingRecords = inspectionMapper.selectList(
          new LambdaQueryWrapper<InspectionRecord>()
              .between(InspectionRecord::getInspectionDate, weekStart, weekEnd));
      Map<String, InspectionRecord> existingBySource = new LinkedHashMap<String, InspectionRecord>();
      for (InspectionRecord existing : existingRecords) {
        String key = sheetSourceKey(existing);
        if (StringUtils.hasText(key)) {
          existingBySource.putIfAbsent(key, existing);
        }
      }

      Long batchId = createImportBatch("Google Sheet merge - " + sheetName, "google-sheet-refresh",
          totalRows, incomingRecords.size(), 0, currentUser);
      int inserted = 0;
      int updatedOpenSpaces = 0;
      int preservedLocalRecords = 0;
      for (InspectionRecord incoming : incomingRecords) {
        InspectionRecord existing = existingBySource.get(sheetSourceKey(incoming));
        if (existing == null) {
          incoming.setImportBatchId(batchId);
          inspectionMapper.insert(incoming);
          inserted++;
        } else if (mergeImportedSheetRecordIfSafe(existing, incoming)) {
          inspectionMapper.updateById(existing);
          updatedOpenSpaces++;
        } else {
          // 已预约、已完成、Unavailable、照片/备注关联记录全部保留，不被 Sheet 刷新覆盖。
          preservedLocalRecords++;
        }
      }
      List<Long> staleOpenSlotIds = staleOpenSheetSlotIds(existingRecords, incomingRecords, sheetGid);
      int removedStaleOpenSpaces = staleOpenSlotIds.isEmpty()
          ? 0 : inspectionMapper.deleteBatchIds(staleOpenSlotIds);
      Map<String, Object> result = new LinkedHashMap<String, Object>();
      result.put("sheetName", sheetName);
      result.put("sheetGid", sheetGid);
      result.put("weekStart", weekStart);
      result.put("weekEnd", weekEnd);
      result.put("inserted", inserted);
      result.put("updatedOpenSpaces", updatedOpenSpaces);
      result.put("removedStaleOpenSpaces", removedStaleOpenSpaces);
      result.put("preservedLocalRecords", preservedLocalRecords);
      return result;
    });
    return merged == null ? new LinkedHashMap<String, Object>() : merged;
  }

  private String sheetSourceKey(InspectionRecord record) {
    Map<String, Object> source = rawJsonMap(record == null ? null : record.getRawJson());
    String gid = trim(stringValue(source.get("sourceSheetGid")));
    String row = trim(stringValue(source.get("sourceRow")));
    return StringUtils.hasText(gid) && StringUtils.hasText(row) ? gid + ":" + row : "";
  }

  List<Long> staleOpenSheetSlotIds(List<InspectionRecord> existingRecords,
                                   List<InspectionRecord> incomingRecords,
                                   Long sheetGid) {
    if (existingRecords == null || existingRecords.isEmpty() || sheetGid == null) {
      return new ArrayList<Long>();
    }
    Set<String> incomingKeys = new HashSet<String>();
    if (incomingRecords != null) {
      for (InspectionRecord incoming : incomingRecords) {
        String key = sheetSourceKey(incoming);
        if (StringUtils.hasText(key)) {
          incomingKeys.add(key);
        }
      }
    }
    String expectedGid = String.valueOf(sheetGid);
    List<Long> staleIds = new ArrayList<Long>();
    for (InspectionRecord existing : existingRecords) {
      if (existing == null || existing.getId() == null || isApplicationBooking(existing)
          || !"Open Slot".equalsIgnoreCase(trim(existing.getStatus()))) {
        continue;
      }
      Map<String, Object> source = rawJsonMap(existing.getRawJson());
      if (!"Google Sheet".equalsIgnoreCase(trim(stringValue(source.get("source"))))
          || !expectedGid.equals(trim(stringValue(source.get("sourceSheetGid"))))) {
        continue;
      }
      String key = sheetSourceKey(existing);
      if (StringUtils.hasText(key) && !incomingKeys.contains(key)) {
        staleIds.add(existing.getId());
      }
    }
    return staleIds;
  }

  boolean mergeImportedSheetRecordIfSafe(InspectionRecord existing, InspectionRecord incoming) {
    if (existing == null || incoming == null || isApplicationBooking(existing)
        || !"Open Slot".equalsIgnoreCase(trim(existing.getStatus()))) {
      return false;
    }
    // 仅开放空位允许被 Sheet 更新；保留主键和批次，使照片等外键关系稳定。
    existing.setRowNumber(incoming.getRowNumber());
    existing.setDayName(incoming.getDayName());
    existing.setStatus(incoming.getStatus());
    existing.setMacId(incoming.getMacId());
    existing.setSales(incoming.getSales());
    existing.setFirstName(incoming.getFirstName());
    existing.setLastName(incoming.getLastName());
    existing.setCustomerName(incoming.getCustomerName());
    existing.setPhoneNumber(incoming.getPhoneNumber());
    existing.setAddress(incoming.getAddress());
    existing.setSuburb(incoming.getSuburb());
    existing.setCityCouncil(incoming.getCityCouncil());
    existing.setInspector(incoming.getInspector());
    existing.setInspectionDate(incoming.getInspectionDate());
    existing.setInspectionTime(incoming.getInspectionTime());
    existing.setProjects(incoming.getProjects());
    existing.setSchedulerRemarks(incoming.getSchedulerRemarks());
    existing.setQuotationTeamReport(incoming.getQuotationTeamReport());
    existing.setRawJson(incoming.getRawJson());
    existing.setUpdatedAt(LocalDateTime.now());
    return true;
  }

  private int parseWeekNumber(String sheetName) {
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)week\\s+(\\d+)").matcher(sheetName);
    if (!matcher.find()) {
      throw new BadRequestException("The selected tab is not a numbered Week sheet.");
    }
    return Integer.parseInt(matcher.group(1));
  }

  LocalDate alignSheetDateToWeek(LocalDate parsedDate, LocalDate weekStart, LocalDate weekEnd) {
    if (parsedDate == null || weekStart == null || weekEnd == null
        || (!parsedDate.isBefore(weekStart) && !parsedDate.isAfter(weekEnd))) {
      return parsedDate;
    }
    // Google returns dates such as "2-Jan" without a year. parseDate initially
    // applies the current year, which is wrong for a Dec/Jan week. Try both years
    // represented by the selected Week tab and retain only a date inside that week.
    for (int year : new int[] { weekStart.getYear(), weekEnd.getYear() }) {
      try {
        LocalDate candidate = parsedDate.withYear(year);
        if (!candidate.isBefore(weekStart) && !candidate.isAfter(weekEnd)) {
          return candidate;
        }
      } catch (java.time.DateTimeException ignored) {
        // A leap-day value can be invalid in one candidate year; try the next year.
      }
    }
    return parsedDate;
  }

  private Map<String, String> googleSheetRow(List<?> row) {
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("daydate", cell(row, 0));
    values.put("status", cell(row, 1));
    values.put("macid", cell(row, 2));
    values.put("sales", cell(row, 3));
    values.put("firstname", cell(row, 4));
    values.put("lastname", cell(row, 5));
    values.put("phonenumber", cell(row, 6));
    values.put("address", cell(row, 7));
    values.put("suburb", cell(row, 8));
    values.put("citycouncil", cell(row, 9));
    values.put("inspector", cell(row, 10));
    values.put("inspectiondate", cell(row, 11));
    values.put("inspectiontime", cell(row, 12));
    values.put("projects", cell(row, 13));
    values.put("remarksschedulerteam", cell(row, 14));
    values.put("quotationteamreport", cell(row, 15));
    return values;
  }

  private String cell(List<?> row, int index) {
    return index < row.size() && row.get(index) != null ? String.valueOf(row.get(index)).trim() : "";
  }

  private boolean isOpenSheetSlot(Map<String, String> values) {
    // Sheet 中预留时间可能尚未选择 Inspector；这仍然是真实空位，预约时再按区域和路线安全分配。
    return !isDataRow(values)
        && StringUtils.hasText(value(values, "inspectiondate"))
        && StringUtils.hasText(value(values, "inspectiontime"));
  }

  private void attachSheetSource(InspectionRecord record, Long sheetGid, String sheetName, int sourceRow) {
    Map<String, Object> source = new LinkedHashMap<String, Object>();
    source.put("source", "Google Sheet");
    source.put("sourceSheetGid", sheetGid);
    source.put("sourceSheetName", sheetName);
    source.put("sourceRow", sourceRow);
    source.put("openSlot", "Open Slot".equalsIgnoreCase(trim(record.getStatus())));
    record.setRawJson(mergeRawJson(record.getRawJson(), source));
  }

  private boolean isApplicationBooking(InspectionRecord record) {
    if (record == null || !StringUtils.hasText(record.getRawJson())) {
      return false;
    }
    Object source = rawJsonMap(record.getRawJson()).get("source");
    return source != null && "Book".equalsIgnoreCase(String.valueOf(source));
  }

  boolean containsNonApplicationWeekRows(List<InspectionRecord> records) {
    return records != null && records.stream().anyMatch(record -> !isApplicationBooking(record));
  }

  private void removeReimportedSlotsConsumedByBookings(Long batchId,
                                                        List<InspectionRecord> applicationBookings) {
    if (batchId == null || applicationBookings == null || applicationBookings.isEmpty()) {
      return;
    }
    List<InspectionRecord> activeBookings = applicationBookings.stream()
        .filter(record -> !Arrays.asList("cancelled", "canceled", "rescheduled")
            .contains(trim(record.getStatus()).toLowerCase(Locale.ENGLISH)))
        .toList();
    if (activeBookings.isEmpty()) {
      return;
    }
    List<InspectionRecord> importedOpenSlots = inspectionMapper.selectList(
        new LambdaQueryWrapper<InspectionRecord>()
            .eq(InspectionRecord::getImportBatchId, batchId)
            .eq(InspectionRecord::getStatus, "Open Slot"));
    List<Long> consumedSlotIds = importedOpenSlots.stream()
        .filter(slot -> activeBookings.stream().anyMatch(booking -> sameBookingSpace(slot, booking)))
        .map(InspectionRecord::getId)
        .filter(Objects::nonNull)
        .toList();
    if (!consumedSlotIds.isEmpty()) {
      inspectionMapper.deleteBatchIds(consumedSlotIds);
    }
  }

  private boolean sameBookingSpace(InspectionRecord left, InspectionRecord right) {
    if (!Objects.equals(left.getInspectionDate(), right.getInspectionDate())
        || minutesOf(left.getInspectionTime()) < 0
        || minutesOf(left.getInspectionTime()) != minutesOf(right.getInspectionTime())) {
      return false;
    }
    // 空 Inspector 的 Sheet 行在预约后会写入实际 Inspector。用来源行识别可防止重新同步时复活已占用空位。
    if (sameSheetSourceRow(left, right)) {
      return true;
    }
    return trim(left.getInspector()).equalsIgnoreCase(trim(right.getInspector()));
  }

  private boolean sameSheetSourceRow(InspectionRecord left, InspectionRecord right) {
    Map<String, Object> leftSource = rawJsonMap(left == null ? null : left.getRawJson());
    Map<String, Object> rightSource = rawJsonMap(right == null ? null : right.getRawJson());
    String leftGid = trim(stringValue(leftSource.get("sourceSheetGid")));
    String rightGid = trim(stringValue(rightSource.get("sourceSheetGid")));
    String leftRow = trim(stringValue(leftSource.get("sourceRow")));
    String rightRow = trim(stringValue(rightSource.get("sourceRow")));
    return StringUtils.hasText(leftGid) && StringUtils.hasText(leftRow)
        && leftGid.equals(rightGid) && leftRow.equals(rightRow);
  }

  private String detectFileType(MultipartFile file) {
    // 通过文件头判断真实格式，优先于扩展名，避免 CSV 改名为 xlsx 后触发 POI 解析异常。
    byte[] header = new byte[8];
    int length = 0;
    try (InputStream inputStream = file.getInputStream()) {
      length = inputStream.read(header);
    } catch (Exception ex) {
      return "";
    }
    if (length >= 4 && header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04) {
      return "xlsx";
    }
    if (length >= 8
        && (header[0] & 0xFF) == 0xD0
        && (header[1] & 0xFF) == 0xCF
        && (header[2] & 0xFF) == 0x11
        && (header[3] & 0xFF) == 0xE0
        && (header[4] & 0xFF) == 0xA1
        && (header[5] & 0xFF) == 0xB1
        && (header[6] & 0xFF) == 0x1A
        && (header[7] & 0xFF) == 0xE1) {
      return "xls";
    }
    return "";
  }

  public Map<String, Object> listRecords(String startDate, String endDate, String inspector, String sales, String keyword, int page, int size) {
    // 列表分页统一限制每页最大 200 条，防止前端误传过大 size 导致数据库压力过高。
    int currentPage = page < 1 ? 1 : page;
    int pageSize = size < 1 ? 10 : Math.min(size, 200);
    LOG.info("Querying imported records startDate={} endDate={} inspector={} sales={} keyword={} page={} size={}",
        startDate, endDate, inspector, sales, keyword, currentPage, pageSize);
    Page<InspectionRecord> pageResult = inspectionMapper.selectPage(new Page<InspectionRecord>(currentPage, pageSize),
        buildRecordFilter(startDate, endDate, inspector, sales, keyword)
            .orderByDesc(InspectionRecord::getUpdatedAt)
            .orderByDesc(InspectionRecord::getCreatedAt)
            .orderByDesc(InspectionRecord::getId));
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("total", pageResult.getTotal());
    data.put("page", currentPage);
    data.put("size", pageSize);
    data.put("records", pageResult.getRecords());
    return data;
  }

  public byte[] exportRecords(String startDate, String endDate, String inspector, String sales, String keyword, List<Long> ids) {
    // 导出规则：有 ids 时按选择导出；无 ids 时按当前查询条件导出全部匹配数据，生成 Excel 并加粗表头。
    LambdaQueryWrapper<InspectionRecord> wrapper = hasIds(ids)
        ? selectedRecordsFilter(ids)
        : buildRecordFilter(startDate, endDate, inspector, sales, keyword);
    LOG.info("Exporting imported records startDate={} endDate={} inspector={} sales={} keyword={} selectedIds={}",
        startDate, endDate, inspector, sales, keyword, ids == null ? 0 : ids.size());
    List<InspectionRecord> records = inspectionMapper.selectList(wrapper
        .orderByDesc(InspectionRecord::getUpdatedAt)
        .orderByDesc(InspectionRecord::getCreatedAt)
        .orderByDesc(InspectionRecord::getId));
    return writeExcel(records);
  }

  public InspectionRecord getRecord(Long id) {
    // 详情、编辑、取消等操作共用该方法保证记录存在性检查一致。
    if (id == null) {
      throw new BadRequestException("Record id is required.");
    }
    InspectionRecord record = inspectionMapper.selectById(id);
    if (record == null) {
      throw new BadRequestException("Inspection record does not exist.");
    }
    return record;
  }

  public InspectionRecord updateRecord(Long id, InspectionRecord record) {
    // 编辑接口只更新业务字段，不修改导入批次 id 和创建时间，保留原始导入来源。
    if (id == null || record == null) {
      throw new BadRequestException("Record id and data are required.");
    }
    if (inspectionMapper.selectById(id) == null) {
      throw new BadRequestException("Inspection record does not exist.");
    }
    LOG.info("Updating inspection record id={} inspector={} date={} time={} product={} sales={}", id,
        record.getInspector(), record.getInspectionDate(), record.getInspectionTime(), record.getProjects(), record.getSales());
    LambdaUpdateWrapper<InspectionRecord> updateWrapper = new LambdaUpdateWrapper<InspectionRecord>()
        .eq(InspectionRecord::getId, id)
        .set(InspectionRecord::getRowNumber, record.getRowNumber())
        .set(InspectionRecord::getDayName, record.getDayName())
        .set(InspectionRecord::getStatus, record.getStatus())
        .set(InspectionRecord::getMacId, record.getMacId())
        .set(InspectionRecord::getSales, record.getSales())
        .set(InspectionRecord::getFirstName, record.getFirstName())
        .set(InspectionRecord::getLastName, record.getLastName())
        .set(InspectionRecord::getCustomerName, record.getCustomerName())
        .set(InspectionRecord::getPhoneNumber, record.getPhoneNumber())
        .set(InspectionRecord::getAddress, record.getAddress())
        .set(InspectionRecord::getSuburb, record.getSuburb())
        .set(InspectionRecord::getCityCouncil, record.getCityCouncil())
        .set(InspectionRecord::getInspector, record.getInspector())
        .set(InspectionRecord::getInspectionDate, record.getInspectionDate())
        .set(InspectionRecord::getInspectionTime, record.getInspectionTime())
        .set(InspectionRecord::getProjects, record.getProjects())
        .set(InspectionRecord::getSchedulerRemarks, record.getSchedulerRemarks())
        .set(InspectionRecord::getQuotationTeamReport, record.getQuotationTeamReport())
        .set(InspectionRecord::getUpdatedAt, LocalDateTime.now());
    int updated = inspectionMapper.update(null, updateWrapper);
    if (updated < 1) {
      return getRecord(id);
    }
    InspectionRecord saved = getRecord(id);
    // Sheet 来源记录编辑后立即回写原行；数据库保存成功但同步失败时会在日志中明确记录原因。
    syncBookChange(saved);
    return saved;
  }

  public InspectionRecord cancelRecord(Long id) {
    // 取消功能采用状态标记而非删除，便于保留客户和排班历史。
    if (id == null) {
      throw new BadRequestException("Record id is required.");
    }
    if (inspectionMapper.selectById(id) == null) {
      throw new BadRequestException("Inspection record does not exist.");
    }
    LOG.info("Cancelling inspection record id={}", id);
    int updated = inspectionMapper.update(null, new LambdaUpdateWrapper<InspectionRecord>()
        .eq(InspectionRecord::getId, id)
        .set(InspectionRecord::getStatus, "Cancelled")
        .set(InspectionRecord::getUpdatedAt, LocalDateTime.now()));
    if (updated < 1) {
      throw new BadRequestException("Inspection record cancel failed.");
    }
    InspectionRecord saved = getRecord(id);
    syncBookChange(saved);
    return saved;
  }

  public int deleteRecord(Long id) {
    // 单条删除用于列表行操作，直接按主键物理删除。
    if (id == null) {
      throw new BadRequestException("Record id is required.");
    }
    LOG.info("Deleting inspection record id={}", id);
    return inspectionMapper.deleteById(id);
  }

  public int deleteRecords(List<Long> ids) {
    // 批量删除先去重和过滤空 id，避免重复 id 影响日志和数据库执行。
    if (ids == null || ids.isEmpty()) {
      throw new BadRequestException("Record ids are required.");
    }
    List<Long> validIds = new ArrayList<Long>();
    for (Long id : ids) {
      if (id != null && !validIds.contains(id)) {
        validIds.add(id);
      }
    }
    if (validIds.isEmpty()) {
      throw new BadRequestException("Record ids are required.");
    }
    LOG.info("Deleting inspection records in bulk count={} ids={}", validIds.size(), validIds);
    return inspectionMapper.deleteBatchIds(validIds);
  }

  public int deleteRecordsByFilter(String startDate, String endDate, String inspector, String sales, String keyword) {
    // 删除全部使用当前查询条件构造同一套筛选器，保证“看到什么条件就删什么范围”。
    LOG.info("Deleting by imported-record filters startDate={} endDate={} inspector={} sales={} keyword={}",
        startDate, endDate, inspector, sales, keyword);
    return inspectionMapper.delete(buildRecordFilter(startDate, endDate, inspector, sales, keyword));
  }

  public int deleteRecordsByRouteFilter(String startDate, String endDate, String routeName, String inspector,
                                        String sales, String keyword) {
    // Routes map 的删除全部按当前页面完整查询条件过滤，避免误删查询范围外的数据。
    LOG.info("Deleting by route filters startDate={} endDate={} route={} inspector={} sales={} keyword={}",
        startDate, endDate, routeName, inspector, sales, keyword);
    return inspectionMapper.delete(buildRouteFilter(startDate, endDate, routeName, inspector, sales, keyword));
  }

  public Map<String, Object> routeOptions(String startDate, String endDate, String inspector, String sales,
                                          String keyword) {
    // 路线下拉选项按 Inspector 聚合，并受 Routes map 顶部查询条件影响。
    LOG.info("Querying route options startDate={} endDate={} inspector={} sales={} keyword={}",
        startDate, endDate, inspector, sales, keyword);
    QueryWrapper<InspectionRecord> wrapper = new QueryWrapper<InspectionRecord>()
        .select("coalesce(nullif(inspector, ''), 'Unassigned') as routeName",
            "count(*) as stopCount",
            "min(inspection_date) as startDate",
            "max(inspection_date) as endDate");
    applyDateFilter(wrapper, startDate, endDate);
    applyRecordTextFilter(wrapper, inspector, sales, keyword);
    wrapper.groupBy("coalesce(nullif(inspector, ''), 'Unassigned')")
        .orderByAsc("routeName");
    List<Map<String, Object>> options = inspectionMapper.selectMaps(wrapper);
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("routes", options);
    return data;
  }

  public Map<String, Object> routeMap(String startDate, String endDate, String routeName, String inspector,
                                       String sales, String keyword, int page, int size) {
    // 路线地图先对完整结果做排程排序再分页，确保跨页仍保持 Date + Time 全局正序。
    int currentPage = page < 1 ? 1 : page;
    int pageSize = size < 1 ? 10 : Math.min(size, 200);
    LOG.info("Querying Routes Map startDate={} endDate={} route={} inspector={} sales={} keyword={} page={} size={}",
        startDate, endDate, routeName, inspector, sales, keyword, currentPage, pageSize);
    List<InspectionRecord> allRecords = inspectionMapper.selectList(
        buildRouteFilter(startDate, endDate, routeName, inspector, sales, keyword));
    sortRouteRecords(allRecords);
    enrichRouteWorkDurations(allRecords);
    long total = allRecords.size();
    long requestedOffset = (long) (currentPage - 1) * pageSize;
    int fromIndex = requestedOffset >= total ? allRecords.size() : (int) requestedOffset;
    int toIndex = Math.min(fromIndex + pageSize, allRecords.size());
    List<InspectionRecord> pageRecords =
        new ArrayList<InspectionRecord>(allRecords.subList(fromIndex, toIndex));
    Map<String, Object> summary = routeSummary(startDate, endDate, routeName, inspector, sales, keyword);
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("summary", summary);
    data.put("total", total);
    data.put("page", currentPage);
    data.put("size", pageSize);
    data.put("records", pageRecords);
    return data;
  }

  public Map<String, Object> routeMapData(String startDate, String endDate, String routeName, String inspector,
                                           String sales, String keyword) {
    // 地图绘制需要当前筛选范围内的完整路线数据，单独提供接口，避免分页列表只返回当前页导致路线断线。
    LOG.info("Querying Google Maps route data startDate={} endDate={} route={} inspector={} sales={} keyword={}",
        startDate, endDate, routeName, inspector, sales, keyword);
    List<InspectionRecord> allRecords = inspectionMapper.selectList(
        buildRouteFilter(startDate, endDate, routeName, inspector, sales, keyword));
    List<InspectionRecord> records = new ArrayList<InspectionRecord>();
    for (InspectionRecord record : allRecords) {
      if (isMappableRouteRecord(record)) {
        records.add(record);
      }
    }
    sortRouteRecords(records);
    enrichRouteWorkDurations(records);
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("records", records);
    data.put("inspectors", inspectorProfiles());
    data.put("total", records.size());
    return data;
  }

  private void sortRouteRecords(List<InspectionRecord> records) {
    records.sort(Comparator
        .comparing(InspectionRecord::getInspectionDate,
            Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparingInt(record -> routeTimeSortValue(record.getInspectionTime()))
        .thenComparing(InspectionRecord::getId,
            Comparator.nullsLast(Comparator.naturalOrder())));
  }

  private int routeTimeSortValue(String value) {
    int seconds = secondsOfDay(value);
    return seconds < 0 ? Integer.MAX_VALUE : seconds;
  }

  private void enrichRouteWorkDurations(List<InspectionRecord> records) {
    for (InspectionRecord record : records) {
      record.setWorkDurationMinutes(routeWorkDurationMinutes(record.getProjects()));
    }
  }

  public Map<String, Object> bookOverview(String dateText, CurrentUser currentUser) {
    // Book 页面初始化数据：产品/检查员枚举、当天已创建预约、可用时段统计。
    // Scheduler/Sales use the server date so the visible range, slot search and save validation agree.
    boolean schedulerMode = currentUser != null
        && Arrays.asList("scheduler", "sales").contains(trim(currentUser.getRoleCode()).toLowerCase(Locale.ENGLISH));
    LocalDate date = schedulerMode ? LocalDate.now() : bookDate(dateText);
    LocalDate endDate = schedulerMode ? date.plusDays(4) : date;
    LOG.info("Querying Book page overview date={}", date);
    List<InspectionRecord> dayRecords = activeRecordsByDate(date);
    List<InspectionRecord> bookedRecords = bookedRecords(dayRecords);
    List<Map<String, Object>> inspectors = inspectorProfiles();
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("date", date);
    data.put("startDate", date);
    data.put("endDate", endDate);
    data.put("schedulerMode", schedulerMode);
    data.put("products", BOOK_PRODUCTS);
    data.put("inspectors", inspectors);
    data.put("areas", bookAreas());
    data.put("timeSlots", bookTimeSlots());
    data.put("bookedToday", bookedRecords.size());
    data.put("inspectorRoutes", inspectors.size());
    // 与原型一致，进入页面时不提前推荐时段；用户完成地址和产品后点击 Find slots 再计算。
    List<InspectionRecord> sheetOpenSlots = openSheetSlots(dayRecords);
    data.put("availableSlots", sheetOpenSlots.size());
    data.put("sheetOpenSlots", sheetOpenSlots);
    // 返回数据库中真实存在的 Sheet 空白日期，前端可引导用户选择已导入的周，而不是显示含糊的“没有时段”。
    data.put("availableDates", availableBookDates());
    data.put("confirmedAppointments", bookedRecords);
    data.put("reschedulePool", reschedulePool());
    return data;
  }

  public Map<String, Object> bookSlots(Map<String, Object> body, CurrentUser currentUser) {
    // Manager/Admin 手动选最多五天及指定时间；Scheduler 固定搜索从今天开始的五天并忽略时间筛选。
    boolean schedulerMode = currentUser != null
        && Arrays.asList("scheduler", "sales").contains(trim(currentUser.getRoleCode()).toLowerCase(Locale.ENGLISH));
    LocalDate startDate = schedulerMode
        ? LocalDate.now()
        : bookDate(stringValue(body == null ? null : body.get("inspectionDate")));
    String endDateText = stringValue(body == null ? null : body.get("inspectionEndDate"));
    LocalDate endDate = schedulerMode
        ? startDate.plusDays(4)
        : (StringUtils.hasText(endDateText) ? parseDate(endDateText) : startDate);
    validateBookDate(startDate);
    validateBookDate(endDate);
    if (endDate.isBefore(startDate)) {
      throw new BadRequestException("Booking end date cannot be before the start date.");
    }
    if (endDate.isAfter(startDate.plusDays(4))) {
      throw new BadRequestException("Booking date range cannot exceed 5 days.");
    }
    String area = stringValue(body == null ? null : body.get("area"));
    String timeSlot = schedulerMode ? "" : stringValue(body == null ? null : body.get("timeSlot"));
    String state = stringValue(body == null ? null : body.get("state"));
    String address = stringValue(body == null ? null : body.get("address"));
    String phone = stringValue(body == null ? null : body.get("phoneNumber"));
    String secondaryPhone = stringValue(body == null ? null : body.get("secondaryPhone"));
    String product = selectedProductLabel(body == null ? null : body.get("products"));
    validateBookContact(phone, secondaryPhone);
    String normalizedState = validateAustralianAddressAndState(address, state);
    validateBookProduct(product);
    int durationMinutes = workDurationMinutes(product);
    LOG.info("Querying Book recommendations startDate={} endDate={} area={} timeSlot={} state={} product={} address={}",
        startDate, endDate, area, timeSlot, state, product, address);
    String matchedArea = resolveBookingArea(area, address);
    List<Map<String, Object>> slots = new ArrayList<Map<String, Object>>();
    List<InspectionRecord> routeRecords = new ArrayList<InspectionRecord>();
    Map<String, Integer> searchSummary = new LinkedHashMap<String, Integer>();
    searchSummary.put("sheetOpenSlots", 0);
    searchSummary.put("unknownInspector", 0);
    searchSummary.put("stateMismatch", 0);
    searchSummary.put("areaMismatch", 0);
    searchSummary.put("invalidTime", 0);
    searchSummary.put("timeFilterMismatch", 0);
    searchSummary.put("overlap", 0);
    searchSummary.put("routeCandidates", 0);
    for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
      slots.addAll(buildBookSlots(date, matchedArea, timeSlot, normalizedState, product, durationMinutes,
          searchSummary));
      routeRecords.addAll(routeAppointments(activeRecordsByDate(date)));
    }
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("date", startDate);
    data.put("startDate", startDate);
    data.put("endDate", endDate);
    data.put("schedulerMode", schedulerMode);
    data.put("product", product);
    data.put("durationMinutes", durationMinutes);
    data.put("matchedArea", matchedArea);
    data.put("slots", slots);
    data.put("availableSlots", slots.size());
    // 将 Sheet 原始空位和各级筛选结果分开返回，避免前端把“有空行但路线不安全”误报成“没有导入”。
    data.put("searchSummary", searchSummary);
    data.put("availableDates", availableBookDates());
    // 路线计算必须包含 IB Passed、NEW CRM 等所有真实排班，不能只看应用内新建的 Fixed/Booked。
    data.put("confirmedAppointments", routeRecords);
    data.put("duplicateWarning", duplicateCustomerWarning(body, longValue(body == null ? null : body.get("rescheduleId"))));
    return data;
  }

  @Transactional
  public InspectionRecord createBookAppointment(Map<String, Object> body, CurrentUser currentUser) {
    // Book 保存预约：校验所选 inspector/date/time 未被占用，然后写入 schedule_inspection 单表。
    if (body == null) {
      throw new BadRequestException("Booking data is required.");
    }
    LocalDate date = bookDate(stringValue(body.get("inspectionDate")));
    validateBookDate(date);
    if (currentUser != null
        && Arrays.asList("scheduler", "sales").contains(trim(currentUser.getRoleCode()).toLowerCase(Locale.ENGLISH))
        && date.isAfter(LocalDate.now().plusDays(4))) {
      throw new BadRequestException("Scheduler bookings must use the recommended 5-day window.");
    }
    Long slotId = longValue(body.get("slotId"));
    if (slotId == null) {
      throw new BadRequestException("Choose an available Google Sheet space.");
    }
    String inspector = requiredString(body.get("inspector"), "Inspector is required.");
    String time = normalizeTimeLabel(requiredString(body.get("inspectionTime"), "Inspection time is required."));
    String customerName = requiredString(body.get("customerName"), "Customer name is required.");
    String phone = requiredString(body.get("phoneNumber"), "Phone is required.");
    String secondaryPhone = stringValue(body.get("secondaryPhone"));
    String address = requiredString(body.get("address"), "Address is required.");
    String product = selectedProductLabel(body.get("products"));
    if (!StringUtils.hasText(product)) {
      product = requiredString(body.get("projects"), "Product is required.");
    }
    validateBookContact(phone, secondaryPhone);
    phone = normalizeAustralianPhone(phone);
    secondaryPhone = normalizeAustralianPhone(secondaryPhone);
    body.put("phoneNumber", phone);
    body.put("secondaryPhone", secondaryPhone);
    String state = validateAustralianAddressAndState(address, stringValue(body.get("state")));
    ensureInspectorServesAddress(inspector, address);
    validateBookProduct(product);
    int durationMinutes = workDurationMinutes(product);
    if (!StringUtils.hasText(time)) {
      throw new BadRequestException("Inspection time must use a valid time such as 13:00.");
    }
    InspectionRecord slotRecord = getRecord(slotId);
    ensureOpenSheetSlot(slotRecord, date, inspector, time);
    Long rescheduleId = longValue(body.get("rescheduleId"));
    ensureBookSlotAvailable(date, inspector, time, durationMinutes, slotId);
    ensureTravelWindowStillAvailable(body, date, inspector, time, durationMinutes, slotId);
    InspectionRecord record = slotRecord;
    record.setStatus("Fixed");
    record.setMacId(stringValue(body.get("macId")));
    record.setSales(stringValue(body.get("sales")));
    record.setCustomerName(customerName);
    record.setFirstName(firstName(customerName));
    record.setLastName(lastName(customerName));
    record.setPhoneNumber(phone);
    record.setAddress(address);
    record.setSuburb(inferSuburb(address));
    record.setCityCouncil(state);
    record.setInspector(inspector);
    record.setInspectionDate(date);
    record.setInspectionTime(time);
    record.setProjects(product);
    record.setSchedulerRemarks(stringValue(body.get("notes")));
    record.setRawJson(bookingRawJson(body, durationMinutes, currentUser, record.getRawJson()));
    record.setUpdatedAt(LocalDateTime.now());
    LOG.info("Saving fixed Book appointment customer={} macId={} inspector={} date={} time={} product={} rescheduleId={} user={}",
        customerName, record.getMacId(), inspector, date, time, product, rescheduleId,
        currentUser == null ? "system" : currentUser.getUsername());
    if (!occupyOpenSheetSlot(record)) {
      throw new BadRequestException("Google Sheet space is no longer available.");
    }
    if (rescheduleId != null && !rescheduleId.equals(record.getId())) {
      InspectionRecord previous = rescheduleRecord(rescheduleId);
      previous.setStatus("Rescheduled");
      previous.setUpdatedAt(LocalDateTime.now());
      inspectionMapper.updateById(previous);
      // 新空位写入成功后，把原 Sheet 行同步为 Rescheduled，避免同一客户在两行都显示为有效预约。
      syncBookChange(previous);
    }
    InspectionRecord saved = getRecord(record.getId());
    syncBookChange(saved);
    return saved;
  }

  @Transactional
  public InspectionRecord updateBookAppointmentTime(Long id, Map<String, Object> body, CurrentUser currentUser) {
    if (id == null || body == null) {
      throw new BadRequestException("Appointment id and time are required.");
    }
    Long slotId = longValue(body.get("slotId"));
    if (slotId == null || slotId.equals(id)) {
      throw new BadRequestException("Choose another available Google Sheet space before moving the appointment.");
    }
    InspectionRecord original = getRecord(id);
    ensureBookAppointment(original);
    InspectionRecord destination = getRecord(slotId);
    ensureOpenSheetSlot(destination, destination.getInspectionDate(), destination.getInspector(),
        destination.getInspectionTime());
    ensureInspectorServesAddress(destination.getInspector(), original.getAddress());
    ensureBookSlotAvailable(destination.getInspectionDate(), destination.getInspector(),
        destination.getInspectionTime(), appointmentDuration(original), destination.getId());

    // 改期必须“搬到”另一条 Sheet 空白行；日期、检查员和时间全部以目标空白行为准。
    copyAppointmentDetails(original, destination);
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("fixed", true);
    metadata.put("bookingStatus", "fixed");
    metadata.put("editedAt", LocalDateTime.now().toString());
    metadata.put("editedBy", currentUser == null ? "system" : currentUser.getUsername());
    metadata.put("movedFromId", original.getId());
    metadata.put("previousDate", original.getInspectionDate());
    metadata.put("previousTime", original.getInspectionTime());
    destination.setRawJson(mergeRawJson(destination.getRawJson(), metadata));
    destination.setUpdatedAt(LocalDateTime.now());
    if (!occupyOpenSheetSlot(destination)) {
      throw new BadRequestException("The selected Google Sheet space is no longer available.");
    }

    original.setStatus("Rescheduled");
    original.setUpdatedAt(LocalDateTime.now());
    inspectionMapper.updateById(original);
    syncBookChange(original);
    InspectionRecord saved = getRecord(destination.getId());
    syncBookChange(saved);
    return saved;
  }

  private boolean occupyOpenSheetSlot(InspectionRecord record) {
    // 状态条件与更新放在同一条 SQL 中，两个调度员同时点击同一空位时只有一个请求能成功。
    Long id = record.getId();
    record.setId(null);
    int updated = inspectionMapper.update(record, new LambdaUpdateWrapper<InspectionRecord>()
        .eq(InspectionRecord::getId, id)
        .eq(InspectionRecord::getStatus, "Open Slot"));
    record.setId(id);
    return updated == 1;
  }

  private void copyAppointmentDetails(InspectionRecord source, InspectionRecord destination) {
    destination.setStatus("Fixed");
    destination.setMacId(source.getMacId());
    destination.setSales(source.getSales());
    destination.setFirstName(source.getFirstName());
    destination.setLastName(source.getLastName());
    destination.setCustomerName(source.getCustomerName());
    destination.setPhoneNumber(source.getPhoneNumber());
    destination.setAddress(source.getAddress());
    destination.setSuburb(source.getSuburb());
    destination.setCityCouncil(source.getCityCouncil());
    destination.setProjects(source.getProjects());
    destination.setSchedulerRemarks(source.getSchedulerRemarks());
    destination.setQuotationTeamReport(source.getQuotationTeamReport());
  }

  public InspectionRecord cancelBookAppointment(Long id, CurrentUser currentUser) {
    if (id == null) {
      throw new BadRequestException("Appointment id is required.");
    }
    InspectionRecord record = getRecord(id);
    ensureBookAppointment(record);
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("bookingStatus", "cancelled");
    metadata.put("cancelledAt", LocalDateTime.now().toString());
    metadata.put("cancelledBy", currentUser == null ? "system" : currentUser.getUsername());
    record.setStatus("Cancelled");
    record.setRawJson(mergeRawJson(record.getRawJson(), metadata));
    record.setUpdatedAt(LocalDateTime.now());
    if (inspectionMapper.updateById(record) < 1) {
      throw new BadRequestException("Appointment cancellation failed.");
    }
    InspectionRecord saved = getRecord(id);
    syncBookChange(saved);
    return saved;
  }

  public Map<String, Object> findLatestCustomerByMacId(String macId) {
    // Book 页面录入 MACID 后可复用历史导入记录，自动带出客户、电话和地址信息。
    if (!StringUtils.hasText(macId)) {
      throw new BadRequestException("MAC ID is required.");
    }
    LOG.info("Querying latest customer details by MACID macId={}", macId);
    List<InspectionRecord> records = inspectionMapper.selectList(new LambdaQueryWrapper<InspectionRecord>()
        .eq(InspectionRecord::getMacId, macId.trim())
        .orderByDesc(InspectionRecord::getUpdatedAt)
        .orderByDesc(InspectionRecord::getCreatedAt)
        .orderByDesc(InspectionRecord::getId)
        .last("LIMIT 1"));
    if (records.isEmpty()) {
      throw new BadRequestException("No customer found by MAC ID.");
    }
    InspectionRecord record = records.get(0);
    Map<String, Object> customer = new LinkedHashMap<String, Object>();
    customer.put("id", record.getId());
    customer.put("macId", record.getMacId());
    customer.put("customerName", record.getCustomerName());
    customer.put("phoneNumber", record.getPhoneNumber());
    customer.put("address", record.getAddress());
    customer.put("state", stateFrom(record.getCityCouncil(), record.getAddress()));
    customer.put("secondaryPhone", "");
    customer.put("email", "");
    if (StringUtils.hasText(record.getRawJson())) {
      try {
        Map<?, ?> raw = objectMapper.readValue(record.getRawJson(), Map.class);
        customer.put("secondaryPhone", stringValue(raw.get("secondaryPhone")));
        customer.put("email", stringValue(raw.get("email")));
      } catch (Exception ex) {
        LOG.warn("Failed to parse extended Book customer details macId={} error={}", macId, ex.getMessage());
      }
    }
    return customer;
  }

  public byte[] exportRouteRecords(String startDate, String endDate, String routeName, String inspector, String sales,
                                   String keyword, List<Long> ids) {
    // Routes map 导出与导入列表导出规则一致：优先导出选中数据，否则导出当前筛选范围，生成 Excel 并加粗表头。
    LambdaQueryWrapper<InspectionRecord> wrapper = hasIds(ids)
        ? selectedRecordsFilter(ids)
        : buildRouteFilter(startDate, endDate, routeName, inspector, sales, keyword);
    LOG.info("Exporting route records startDate={} endDate={} route={} inspector={} sales={} keyword={} selectedIds={}",
        startDate, endDate, routeName, inspector, sales, keyword, ids == null ? 0 : ids.size());
    List<InspectionRecord> records = inspectionMapper.selectList(wrapper);
    sortRouteRecords(records);
    return writeExcel(records);
  }

  private List<InspectionRecord> parseCsv(MultipartFile file, ImportResult result) throws Exception {
    // CSV 解析使用 UTF-8 和表头映射，空行/分组行只用于识别当前 Day，不写入业务记录。
    LOG.info("Starting CSV parsing fileName={}", file.getOriginalFilename());
    List<InspectionRecord> records = new ArrayList<InspectionRecord>();
    String currentDay = "";
    try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
         CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreEmptyLines(false).parse(reader)) {
      for (CSVRecord csvRecord : parser) {
        result.setTotalRows(result.getTotalRows() + 1);
        Map<String, String> values = normalizeCsvRecord(csvRecord);
        if (!isDataRow(values)) {
          currentDay = updateCurrentDay(values, currentDay);
          continue;
        }
        try {
          records.add(toInspectionRecord(values, currentDay, (int) csvRecord.getRecordNumber() + 1));
        } catch (Exception ex) {
          result.getErrors().add("Row " + (csvRecord.getRecordNumber() + 1) + ": " + ex.getMessage());
        }
      }
    }
    LOG.info("CSV parsing completed fileName={} validRows={} scannedRows={}", file.getOriginalFilename(),
        records.size(), result.getTotalRows());
    return records;
  }

  private List<InspectionRecord> parseWorkbook(MultipartFile file, ImportResult result) throws Exception {
    // Excel 解析只读取第一个 Sheet，第一行作为表头，后续行按标准导入模板转换字段。
    LOG.info("Starting Excel parsing fileName={}", file.getOriginalFilename());
    List<InspectionRecord> records = new ArrayList<InspectionRecord>();
    DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
    String currentDay = "";
    try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
      Sheet sheet = workbook.getSheetAt(0);
      Row header = sheet.getRow(0);
      if (header == null) {
        throw new BadRequestException("Excel header row is empty.");
      }
      Map<Integer, String> headers = new LinkedHashMap<Integer, String>();
      for (Cell cell : header) {
        headers.put(cell.getColumnIndex(), normalizeHeader(formatter.formatCellValue(cell)));
      }
      for (int i = 1; i <= sheet.getLastRowNum(); i++) {
        Row row = sheet.getRow(i);
        if (row == null) {
          continue;
        }
        result.setTotalRows(result.getTotalRows() + 1);
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Map.Entry<Integer, String> entry : headers.entrySet()) {
          values.put(entry.getValue(), trim(formatter.formatCellValue(row.getCell(entry.getKey()))));
        }
        if (!isDataRow(values)) {
          currentDay = updateCurrentDay(values, currentDay);
          continue;
        }
        try {
          records.add(toInspectionRecord(values, currentDay, i + 1));
        } catch (Exception ex) {
          result.getErrors().add("Row " + (i + 1) + ": " + ex.getMessage());
        }
      }
    }
    LOG.info("Excel parsing completed fileName={} validRows={} scannedRows={}", file.getOriginalFilename(),
        records.size(), result.getTotalRows());
    return records;
  }

  private Map<String, String> normalizeCsvRecord(CSVRecord record) {
    // 表头先标准化再取值，兼容空格、大小写、特殊符号不同的导入文件。
    Map<String, String> values = new LinkedHashMap<String, String>();
    for (Map.Entry<String, String> entry : record.toMap().entrySet()) {
      values.put(normalizeHeader(entry.getKey()), trim(entry.getValue()));
    }
    return values;
  }

  private InspectionRecord toInspectionRecord(Map<String, String> values, String currentDay, int rowNumber) throws Exception {
    // 将导入模板字段转换为数据库实体，同时把原始行 JSON 保存下来便于后续排查。
    InspectionRecord record = new InspectionRecord();
    record.setRowNumber(rowNumber);
    record.setDayName(StringUtils.hasText(value(values, "daydate")) ? value(values, "daydate") : currentDay);
    record.setStatus(value(values, "status"));
    record.setMacId(value(values, "macid"));
    record.setSales(value(values, "sales"));
    record.setFirstName(value(values, "firstname"));
    record.setLastName(value(values, "lastname"));
    record.setCustomerName(trim(record.getFirstName() + " " + record.getLastName()));
    record.setPhoneNumber(value(values, "phonenumber"));
    record.setAddress(value(values, "address"));
    record.setSuburb(value(values, "suburb"));
    record.setCityCouncil(value(values, "citycouncil"));
    record.setInspector(value(values, "inspector"));
    record.setInspectionDate(parseDate(value(values, "inspectiondate")));
    record.setInspectionTime(value(values, "inspectiontime"));
    record.setProjects(value(values, "projects"));
    record.setSchedulerRemarks(value(values, "remarksschedulerteam"));
    record.setQuotationTeamReport(value(values, "quotationteamreport"));
    record.setRawJson(objectMapper.writeValueAsString(values));
    LocalDateTime now = LocalDateTime.now();
    record.setCreatedAt(now);
    record.setUpdatedAt(now);
    return record;
  }

  private boolean isDataRow(Map<String, String> values) {
    // 有客户、MAC、电话或地址任一核心字段，才认为是有效业务记录。
    return StringUtils.hasText(value(values, "macid"))
        || StringUtils.hasText(value(values, "address"))
        || StringUtils.hasText(value(values, "phonenumber"))
        || StringUtils.hasText(value(values, "firstname"))
        || StringUtils.hasText(value(values, "lastname"));
  }

  private String updateCurrentDay(Map<String, String> values, String currentDay) {
    // 模板中可能用分组行表示日期/星期，数据行缺失 Day 时沿用最近一次分组值。
    String day = value(values, "daydate");
    if (StringUtils.hasText(day)) {
      String maybeDate = value(values, "status");
      return StringUtils.hasText(maybeDate) ? day + " " + maybeDate : day;
    }
    return currentDay;
  }

  private LambdaQueryWrapper<InspectionRecord> buildRecordFilter(String startDate, String endDate, String inspector, String sales, String keyword) {
    // Inspection Import 列表查询条件集中在这里维护，查询、导出、删除全部共用同一套逻辑。
    LambdaQueryWrapper<InspectionRecord> wrapper = new LambdaQueryWrapper<InspectionRecord>();
    applyDateFilter(wrapper, startDate, endDate);
    if (StringUtils.hasText(inspector)) {
      wrapper.eq(InspectionRecord::getInspector, inspector.trim());
    }
    if (StringUtils.hasText(sales)) {
      wrapper.eq(InspectionRecord::getSales, sales.trim());
    }
    if (StringUtils.hasText(keyword)) {
      String trimmed = keyword.trim();
      wrapper.and(item -> item.like(InspectionRecord::getCustomerName, trimmed)
          .or().like(InspectionRecord::getMacId, trimmed)
          .or().like(InspectionRecord::getPhoneNumber, trimmed)
          .or().like(InspectionRecord::getAddress, trimmed)
          .or().like(InspectionRecord::getSales, trimmed));
    }
    return wrapper;
  }

  private LambdaQueryWrapper<InspectionRecord> buildRouteFilter(String startDate, String endDate, String routeName,
                                                               String inspector, String sales, String keyword) {
    // Routes map 查询条件集中封装，避免分页、统计、导出、删除使用不同过滤口径。
    LambdaQueryWrapper<InspectionRecord> wrapper = buildRecordFilter(startDate, endDate, inspector, sales, keyword);
    applyRouteFilter(wrapper, routeName);
    return wrapper;
  }

  private LambdaQueryWrapper<InspectionRecord> selectedRecordsFilter(List<Long> ids) {
    // 选择导出/批量操作只允许使用有效 id 列表，空列表直接提示业务错误。
    List<Long> validIds = uniqueIds(ids);
    if (validIds.isEmpty()) {
      throw new BadRequestException("Record ids are required.");
    }
    return new LambdaQueryWrapper<InspectionRecord>().in(InspectionRecord::getId, validIds);
  }

  private boolean hasIds(List<Long> ids) {
    return ids != null && !ids.isEmpty();
  }

  private List<Long> uniqueIds(List<Long> ids) {
    // 保留原始选择顺序并去重，方便日志排查和稳定导出。
    List<Long> validIds = new ArrayList<Long>();
    if (ids == null) {
      return validIds;
    }
    for (Long id : ids) {
      if (id != null && !validIds.contains(id)) {
        validIds.add(id);
      }
    }
    return validIds;
  }

  private byte[] writeExcel(List<InspectionRecord> records) {
    // Excel 导出使用 xlsx 格式，表头行设置粗体，满足下载后在 Excel 中查看样式的需求。
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      LOG.info("Starting Excel export generation rows={}", records == null ? 0 : records.size());
      Sheet sheet = workbook.createSheet("Inspection Records");
      CellStyle headerStyle = workbook.createCellStyle();
      Font headerFont = workbook.createFont();
      headerFont.setBold(true);
      headerStyle.setFont(headerFont);

      String[] headers = exportHeaders();
      Row headerRow = sheet.createRow(0);
      for (int index = 0; index < headers.length; index++) {
        Cell cell = headerRow.createCell(index);
        cell.setCellValue(headers[index]);
        cell.setCellStyle(headerStyle);
      }

      int rowIndex = 1;
      if (records != null) {
        for (InspectionRecord record : records) {
          Row row = sheet.createRow(rowIndex++);
          Object[] values = exportRowValues(record);
          for (int index = 0; index < values.length; index++) {
            row.createCell(index).setCellValue(stringValue(values[index]));
          }
        }
      }
      for (int index = 0; index < headers.length; index++) {
        sheet.autoSizeColumn(index);
      }
      workbook.write(outputStream);
      LOG.info("Excel export generated bytes={}", outputStream.size());
      return outputStream.toByteArray();
    } catch (Exception ex) {
      LOG.error("Excel export generation failed error={}", ex.getMessage(), ex);
      throw new BadRequestException("Export failed: " + ex.getMessage());
    }
  }

  private String[] exportHeaders() {
    return new String[] {
        "ID", "Batch ID", "Row", "Day", "Status", "MAC ID", "Sales", "First name", "Last name",
        "Customer", "Phone", "Address", "Suburb", "City council", "Inspector", "Date", "Time", "Product",
        "Scheduler remarks", "Quotation team report", "Created at", "Updated at"
    };
  }

  private Object[] exportRowValues(InspectionRecord record) {
    return new Object[] {
        record.getId(), record.getImportBatchId(), record.getRowNumber(), record.getDayName(), record.getStatus(),
        record.getMacId(), record.getSales(), record.getFirstName(), record.getLastName(), record.getCustomerName(),
        record.getPhoneNumber(), record.getAddress(), record.getSuburb(), record.getCityCouncil(),
        record.getInspector(), record.getInspectionDate(), record.getInspectionTime(), record.getProjects(),
        record.getSchedulerRemarks(), record.getQuotationTeamReport(), record.getCreatedAt(), record.getUpdatedAt()
    };
  }

  private String stringValue(Object value) {
    // Excel 单元格统一把 null 转为空字符串，日期时间沿用对象默认 ISO 文本，避免导出出现 null 字样。
    return value == null ? "" : String.valueOf(value);
  }

  private void applyDateFilter(LambdaQueryWrapper<InspectionRecord> wrapper, String startDate, String endDate) {
    // LambdaQueryWrapper 用于 MyBatis-Plus 单表实体查询，避免手写列名。
    LocalDate start = parseDate(startDate);
    LocalDate end = parseDate(endDate);
    if (start != null) {
      wrapper.ge(InspectionRecord::getInspectionDate, start);
    }
    if (end != null) {
      wrapper.le(InspectionRecord::getInspectionDate, end);
    }
  }

  private void applyDateFilter(QueryWrapper<InspectionRecord> wrapper, String startDate, String endDate) {
    // QueryWrapper 用于聚合统计场景，需要直接引用数据库列名和 SQL 表达式。
    LocalDate start = parseDate(startDate);
    LocalDate end = parseDate(endDate);
    if (start != null) {
      wrapper.ge("inspection_date", start);
    }
    if (end != null) {
      wrapper.le("inspection_date", end);
    }
  }

  private void applyRecordTextFilter(QueryWrapper<InspectionRecord> wrapper, String inspector, String sales,
                                     String keyword) {
    // QueryWrapper 聚合场景复用导入列表查询条件，字段名对应 schedule_inspection 表列。
    if (StringUtils.hasText(inspector)) {
      wrapper.eq("inspector", inspector.trim());
    }
    if (StringUtils.hasText(sales)) {
      wrapper.eq("sales", sales.trim());
    }
    if (StringUtils.hasText(keyword)) {
      String trimmed = keyword.trim();
      wrapper.and(item -> item.like("customer_name", trimmed)
          .or().like("mac_id", trimmed)
          .or().like("phone_number", trimmed)
          .or().like("address", trimmed)
          .or().like("sales", trimmed));
    }
  }

  private void applyRouteFilter(LambdaQueryWrapper<InspectionRecord> wrapper, String routeName) {
    // Unassigned 是前端显示值，实际数据库中对应 inspector 为空或空字符串。
    if (!StringUtils.hasText(routeName)) {
      return;
    }
    if ("Unassigned".equalsIgnoreCase(routeName.trim())) {
      wrapper.and(item -> item.isNull(InspectionRecord::getInspector)
          .or().eq(InspectionRecord::getInspector, ""));
    } else {
      wrapper.eq(InspectionRecord::getInspector, routeName.trim());
    }
  }

  private void applyRouteFilter(QueryWrapper<InspectionRecord> wrapper, String routeName) {
    // 聚合 SQL 场景下使用字符串列名处理 Unassigned 路线过滤。
    if (!StringUtils.hasText(routeName)) {
      return;
    }
    if ("Unassigned".equalsIgnoreCase(routeName.trim())) {
      wrapper.and(item -> item.isNull("inspector").or().eq("inspector", ""));
    } else {
      wrapper.eq("inspector", routeName.trim());
    }
  }

  private Map<String, Object> routeSummary(String startDate, String endDate, String routeName, String inspector,
                                           String sales, String keyword) {
    // 汇总卡片一次性统计路线组数、总巡检数、未排时间数量和日期范围。
    QueryWrapper<InspectionRecord> wrapper = new QueryWrapper<InspectionRecord>()
        .select("count(distinct coalesce(nullif(inspector, ''), 'Unassigned')) as routeGroups",
            "count(*) as scheduledInspections",
            // Google Sheet 可预约空位带有 inspection_time，应按 Open Slot 状态统计，不能按空时间统计。
            "sum(case when lower(trim(coalesce(status, ''))) = 'open slot' then 1 else 0 end) as openSlots",
            "min(inspection_date) as startDate",
            "max(inspection_date) as endDate");
    applyDateFilter(wrapper, startDate, endDate);
    applyRecordTextFilter(wrapper, inspector, sales, keyword);
    applyRouteFilter(wrapper, routeName);
    List<Map<String, Object>> rows = inspectionMapper.selectMaps(wrapper);
    if (!rows.isEmpty()) {
      return rows.get(0);
    }
    Map<String, Object> summary = new LinkedHashMap<String, Object>();
    summary.put("routeGroups", 0);
    summary.put("scheduledInspections", 0);
    summary.put("openSlots", 0);
    summary.put("startDate", null);
    summary.put("endDate", null);
    return summary;
  }

  private boolean isMappableRouteRecord(InspectionRecord record) {
    // 地图只绘制真正的客户巡检点；导入文件中的 Total Kms、空时间和空日期备注行不参与连线。
    return record != null
        && record.getInspectionDate() != null
        && StringUtils.hasText(record.getInspectionTime())
        && StringUtils.hasText(record.getAddress())
        && !trim(record.getAddress()).toLowerCase(Locale.ENGLISH).startsWith("total kms");
  }

  private LocalDate bookDate(String value) {
    LocalDate parsed = parseDate(value);
    return parsed == null ? LocalDate.now() : parsed;
  }

  private List<InspectionRecord> activeRecordsByDate(LocalDate date) {
    return inspectionMapper.selectList(new LambdaQueryWrapper<InspectionRecord>()
        .eq(InspectionRecord::getInspectionDate, date)
        .and(item -> item.isNull(InspectionRecord::getStatus)
            .or().notIn(InspectionRecord::getStatus, Arrays.asList("Cancelled", "Reschedule", "Rescheduled")))
        .orderByAsc(InspectionRecord::getInspectionDate)
        .orderByAsc(InspectionRecord::getId));
  }

  private List<InspectionRecord> bookedRecords(List<InspectionRecord> records) {
    List<InspectionRecord> booked = new ArrayList<InspectionRecord>();
    for (InspectionRecord record : records) {
      String status = trim(record.getStatus());
      if ("Booked".equalsIgnoreCase(status) || "Fixed".equalsIgnoreCase(status)) {
        booked.add(record);
      }
    }
    booked.sort(Comparator.comparingInt(record -> minutesOf(record.getInspectionTime())));
    return booked;
  }

  private List<InspectionRecord> openSheetSlots(List<InspectionRecord> records) {
    List<InspectionRecord> slots = new ArrayList<InspectionRecord>();
    Set<String> occupiedTimes = new HashSet<String>();
    for (InspectionRecord record : records) {
      if (!"Open Slot".equalsIgnoreCase(trim(record.getStatus()))
          && StringUtils.hasText(record.getInspector())
          && record.getInspectionDate() != null
          && minutesOf(record.getInspectionTime()) >= 0) {
        occupiedTimes.add(bookSlotKey(record));
      }
    }
    for (InspectionRecord record : records) {
      if ("Open Slot".equalsIgnoreCase(trim(record.getStatus()))
          && record.getInspectionDate() != null
          && StringUtils.hasText(record.getInspectionTime())
          // 同一 inspector/date/time 已有真实排班时，即使数据库残留空位行也绝不能再次推荐。
          && !occupiedTimes.contains(bookSlotKey(record))) {
        slots.add(record);
      }
    }
    slots.sort(Comparator.comparingInt(record -> minutesOf(record.getInspectionTime())));
    return slots;
  }

  private List<InspectionRecord> routeAppointments(List<InspectionRecord> records) {
    List<InspectionRecord> appointments = new ArrayList<InspectionRecord>();
    for (InspectionRecord record : records) {
      if (!"Open Slot".equalsIgnoreCase(trim(record.getStatus())) && isMappableRouteRecord(record)) {
        // Book 的前后行程计算必须使用预约实际工时；不能让未知产品标签把 30/45 分钟预约误算成 60 分钟。
        record.setWorkDurationMinutes(appointmentDuration(record));
        appointments.add(record);
      }
    }
    appointments.sort(Comparator.comparingInt(record -> minutesOf(record.getInspectionTime())));
    return appointments;
  }

  private String bookSlotKey(InspectionRecord record) {
    return trim(record.getInspector()).toLowerCase(Locale.ENGLISH) + "\u0000"
        + stringValue(record.getInspectionDate()) + "\u0000"
        + minutesOf(record.getInspectionTime());
  }

  private List<LocalDate> availableBookDates() {
    List<InspectionRecord> records = inspectionMapper.selectList(new LambdaQueryWrapper<InspectionRecord>()
        .eq(InspectionRecord::getStatus, "Open Slot")
        .isNotNull(InspectionRecord::getInspectionDate)
        // 历史空白行只用于保留 Sheet 记录，不能再次作为新预约时段。
        .ge(InspectionRecord::getInspectionDate, LocalDate.now())
        .orderByAsc(InspectionRecord::getInspectionDate));
    List<LocalDate> dates = new ArrayList<LocalDate>();
    for (InspectionRecord record : records) {
      LocalDate date = record.getInspectionDate();
      if (date != null && !dates.contains(date)) {
        dates.add(date);
      }
    }
    return dates;
  }

  private void validateBookDate(LocalDate date) {
    // 前端 min 属性只改善操作体验；后端仍必须阻止绕过页面提交过去日期。
    if (date == null || date.isBefore(LocalDate.now())) {
      throw new BadRequestException("Inspection date cannot be in the past.");
    }
  }

  private List<InspectionRecord> reschedulePool() {
    List<InspectionRecord> records = inspectionMapper.selectList(new LambdaQueryWrapper<InspectionRecord>()
        .eq(InspectionRecord::getStatus, "Reschedule")
        .orderByDesc(InspectionRecord::getUpdatedAt)
        .orderByDesc(InspectionRecord::getCreatedAt)
        .orderByDesc(InspectionRecord::getId)
        .last("LIMIT 50"));
    Map<String, InspectionRecord> unique = new LinkedHashMap<String, InspectionRecord>();
    for (InspectionRecord record : records) {
      String key = String.join("\u0000",
          trim(record.getMacId()).toLowerCase(Locale.ENGLISH),
          trim(record.getAddress()).toLowerCase(Locale.ENGLISH),
          stringValue(record.getInspectionDate()),
          trim(record.getInspectionTime()).toLowerCase(Locale.ENGLISH));
      unique.putIfAbsent(key, record);
    }
    return new ArrayList<InspectionRecord>(unique.values());
  }

  private List<Map<String, Object>> buildBookSlots(LocalDate date, String area, String timeSlot, String state,
                                                   String product, int durationMinutes,
                                                   Map<String, Integer> searchSummary) {
    List<InspectionRecord> records = activeRecordsByDate(date);
    Map<String, List<BusySlot>> busySlots = busySlotsByInspector(records);
    List<InspectorProfile> profiles = bookInspectorProfiles();
    String normalizedArea = trim(area);
    String normalizedState = stateFrom(state, "");
    String requestedStart = timeSlotStart(timeSlot);
    List<Map<String, Object>> slots = new ArrayList<Map<String, Object>>();
    List<InspectionRecord> openSlots = openSheetSlots(records);
    addSearchCount(searchSummary, "sheetOpenSlots", openSlots.size());
    for (InspectionRecord openSlot : openSlots) {
      List<InspectorProfile> slotProfiles = new ArrayList<InspectorProfile>();
      if (StringUtils.hasText(openSlot.getInspector())) {
        InspectorProfile assignedProfile = inspectorProfile(openSlot.getInspector(), profiles);
        if (assignedProfile != null) {
          slotProfiles.add(assignedProfile);
        }
      } else {
        // 未指定 Inspector 的 Sheet 空位可由同州、同区域且时间不冲突的人员承接。
        slotProfiles.addAll(profiles);
      }
      if (slotProfiles.isEmpty()) {
        addSearchCount(searchSummary, "unknownInspector", 1);
        continue;
      }
      int minute = minutesOf(openSlot.getInspectionTime());
      if (minute < 0) {
        addSearchCount(searchSummary, "invalidTime", 1);
        continue;
      }
      String startText = timeLabel(minute);
      if (StringUtils.hasText(requestedStart) && !requestedStart.equals(startText)) {
        addSearchCount(searchSummary, "timeFilterMismatch", 1);
        continue;
      }
      for (InspectorProfile profile : slotProfiles) {
        if (!inspectorMatchesState(profile, normalizedState)) {
          addSearchCount(searchSummary, "stateMismatch", 1);
          continue;
        }
        if (!inspectorMatchesArea(profile, normalizedArea)) {
          addSearchCount(searchSummary, "areaMismatch", 1);
          continue;
        }
        String inspectorKey = profile.name.toLowerCase(Locale.ENGLISH);
        int workload = busySlots.get(inspectorKey) == null ? 0 : busySlots.get(inspectorKey).size();
        int travelMinutes = estimatedTravelMinutes(profile, normalizedArea, normalizedState);
        if (slotOverlaps(busySlots.get(inspectorKey), minute, durationMinutes)) {
          addSearchCount(searchSummary, "overlap", 1);
          continue;
        }
        Map<String, Object> slot = new LinkedHashMap<String, Object>();
        slot.put("slotId", openSlot.getId());
        slot.put("sourceRow", openSlot.getRowNumber());
        slot.put("inspector", profile.name);
        slot.put("area", profile.area);
        slot.put("base", profile.base);
        slot.put("date", date);
        slot.put("start", startText);
        slot.put("end", timeLabel(minute + durationMinutes));
        slot.put("product", product);
        slot.put("durationMinutes", durationMinutes);
        slot.put("route", profile.area + " · starts from " + profile.base);
        slot.put("workload", workload);
        slot.put("reason", "blank Google Sheet row " + openSlot.getRowNumber() + "; "
            + recommendationReason(profile, normalizedArea, normalizedState, workload));
        slot.put("travelMinutes", travelMinutes);
        slot.put("travel", "Area estimate " + travelMinutes + " min from route base");
        slot.put("planningMinutes", durationMinutes + travelMinutes);
        slots.add(slot);
        addSearchCount(searchSummary, "routeCandidates", 1);
      }
    }
    slots.sort((left, right) -> {
      int travelCompare = Integer.compare((Integer) left.get("travelMinutes"), (Integer) right.get("travelMinutes"));
      if (travelCompare != 0) return travelCompare;
      int workloadCompare = Integer.compare((Integer) left.get("workload"), (Integer) right.get("workload"));
      if (workloadCompare != 0) return workloadCompare;
      int timeCompare = String.valueOf(left.get("start")).compareTo(String.valueOf(right.get("start")));
      if (timeCompare != 0) return timeCompare;
      return String.valueOf(left.get("inspector")).compareTo(String.valueOf(right.get("inspector")));
    });
    for (int index = 0; index < slots.size(); index++) {
      slots.get(index).put("recommendation", index == 0 ? "Best" : "Option " + (index + 1));
    }
    return slots;
  }

  private void addSearchCount(Map<String, Integer> summary, String key, int amount) {
    if (summary == null || amount == 0) {
      return;
    }
    summary.put(key, summary.getOrDefault(key, 0) + amount);
  }

  private InspectorProfile inspectorProfile(String name, List<InspectorProfile> profiles) {
    for (InspectorProfile profile : profiles) {
      if (profile.name.equalsIgnoreCase(trim(name))) {
        return profile;
      }
    }
    return null;
  }

  private Map<String, List<BusySlot>> busySlotsByInspector(List<InspectionRecord> records) {
    Map<String, List<BusySlot>> busy = new LinkedHashMap<String, List<BusySlot>>();
    for (InspectionRecord record : records) {
      if ("Open Slot".equalsIgnoreCase(trim(record.getStatus()))) {
        continue;
      }
      String inspector = trim(record.getInspector());
      int minute = minutesOf(record.getInspectionTime());
      if (!StringUtils.hasText(inspector) || minute < 0) {
        continue;
      }
      busy.computeIfAbsent(inspector.toLowerCase(Locale.ENGLISH), key -> new ArrayList<BusySlot>())
          .add(new BusySlot(minute, appointmentDuration(record)));
    }
    return busy;
  }

  private boolean slotOverlaps(List<BusySlot> existingSlots, int candidateStart, int candidateDuration) {
    if (existingSlots == null || existingSlots.isEmpty()) {
      return false;
    }
    int candidateEnd = candidateStart + candidateDuration;
    for (BusySlot existing : existingSlots) {
      if (existing != null && existing.start < candidateEnd
          && existing.start + existing.durationMinutes > candidateStart) {
        return true;
      }
    }
    return false;
  }

  private void ensureOpenSheetSlot(InspectionRecord slot, LocalDate date, String inspector, String time) {
    boolean inspectorCompatible = slot != null
        && (!StringUtils.hasText(slot.getInspector())
            || trim(inspector).equalsIgnoreCase(trim(slot.getInspector())));
    if (slot == null || !"Open Slot".equalsIgnoreCase(trim(slot.getStatus()))
        || !date.equals(slot.getInspectionDate())
        || !inspectorCompatible
        || minutesOf(time) != minutesOf(slot.getInspectionTime())) {
      throw new BadRequestException("The selected Google Sheet space is no longer available.");
    }
  }

  private void ensureBookSlotAvailable(LocalDate date, String inspector, String time,
                                        int durationMinutes, Long excludedId) {
    List<InspectionRecord> records = inspectionMapper.selectList(new LambdaQueryWrapper<InspectionRecord>()
        .eq(InspectionRecord::getInspectionDate, date)
        .eq(InspectionRecord::getInspector, inspector)
        .ne(excludedId != null, InspectionRecord::getId, excludedId)
        .and(item -> item.isNull(InspectionRecord::getStatus)
            .or().notIn(InspectionRecord::getStatus,
                Arrays.asList("Cancelled", "Reschedule", "Rescheduled", "Open Slot"))));
    int requestedStart = minutesOf(time);
    List<BusySlot> existingSlots = new ArrayList<BusySlot>();
    for (InspectionRecord record : records) {
      int existingStart = minutesOf(record.getInspectionTime());
      if (existingStart >= 0) {
        existingSlots.add(new BusySlot(existingStart, appointmentDuration(record)));
      }
    }
    if (slotOverlaps(existingSlots, requestedStart, durationMinutes)) {
      throw new BadRequestException("Selected slot overlaps an existing appointment.");
    }
  }

  private void ensureTravelWindowStillAvailable(Map<String, Object> body, LocalDate date, String inspector,
                                                 String time, int durationMinutes, Long slotId) {
    // 前端使用 Google 路线计算真实行车时间；保存前再次核对前后预约和时间间隔，防止旧推荐被直接提交。
    Object rawVerification = body.get("travelVerification");
    if (!(rawVerification instanceof Map)) {
      throw new BadRequestException("Google travel time must be verified before booking.");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> verification = (Map<String, Object>) rawVerification;
    if (!Boolean.TRUE.equals(verification.get("verified"))) {
      throw new BadRequestException("Google travel time must be verified before booking.");
    }
    Integer travelIn = integerValue(verification.get("travelInMinutes"));
    Integer travelOut = integerValue(verification.get("travelOutMinutes"));
    Integer buffer = integerValue(verification.get("travelBufferMinutes"));
    if (travelIn == null || travelIn < 0 || travelOut == null || travelOut < 0 || buffer == null || buffer < 10) {
      throw new BadRequestException("Travel-time verification is incomplete. Click Find slots again.");
    }

    int requestedStart = minutesOf(time);
    int requestedEnd = requestedStart + durationMinutes;
    InspectionRecord previous = null;
    InspectionRecord next = null;
    int previousEnd = -1;
    int nextStart = Integer.MAX_VALUE;
    int routeStopCount = 0;
    for (InspectionRecord record : activeRecordsByDate(date)) {
      if (record == null || (slotId != null && slotId.equals(record.getId()))
          || !trim(inspector).equalsIgnoreCase(trim(record.getInspector()))
          || "Open Slot".equalsIgnoreCase(trim(record.getStatus()))) {
        continue;
      }
      int existingStart = minutesOf(record.getInspectionTime());
      if (existingStart < 0) {
        continue;
      }
      if (StringUtils.hasText(record.getAddress())) {
        routeStopCount++;
      }
      int existingEnd = existingStart + appointmentDuration(record);
      if (existingEnd <= requestedStart && existingEnd > previousEnd) {
        previous = record;
        previousEnd = existingEnd;
      }
      if (existingStart >= requestedEnd && existingStart < nextStart) {
        next = record;
        nextStart = existingStart;
      }
    }

    Integer checkedPreviousEnd = integerValue(verification.get("previousEndMinutes"));
    Integer checkedNextStart = integerValue(verification.get("nextStartMinutes"));
    if ((previous == null && checkedPreviousEnd != null)
        || (previous != null && (checkedPreviousEnd == null || checkedPreviousEnd != previousEnd))
        || (next == null && checkedNextStart != null)
        || (next != null && (checkedNextStart == null || checkedNextStart != nextStart))) {
      throw new BadRequestException("The inspector route changed. Click Find slots to check travel time again.");
    }
    if (previous != null && !trim(previous.getAddress()).equalsIgnoreCase(trim(stringValue(verification.get("travelFrom"))))) {
      throw new BadRequestException("The previous inspection address changed. Click Find slots again.");
    }
    if (next != null && !trim(next.getAddress()).equalsIgnoreCase(trim(stringValue(verification.get("travelTo"))))) {
      throw new BadRequestException("The next inspection address changed. Click Find slots again.");
    }
    if (previous != null && requestedStart - previousEnd < travelIn + buffer) {
      throw new BadRequestException("Not enough time to drive from the previous inspection to this booking.");
    }
    if (next != null && nextStart - requestedEnd < travelOut + buffer) {
      throw new BadRequestException("Not enough time to drive from this booking to the next inspection.");
    }
    // 当该 inspector 当天已有至少两个真实站点时，必须完成局部三点路线检查，阻止北→南→中间等折返顺序。
    String continuityMode = stringValue(verification.get("routeContinuityMode"));
    if (routeStopCount >= 2) {
      Integer routePath = integerValue(verification.get("routePathMinutes"));
      Integer routeDirect = integerValue(verification.get("routeDirectMinutes"));
      Integer routeDetour = integerValue(verification.get("routeDetourMinutes"));
      if (!Boolean.TRUE.equals(verification.get("routeContinuous"))
          || !StringUtils.hasText(continuityMode)
          || routePath == null || routePath <= 0
          || routeDirect == null || routeDirect <= 0
          || routeDetour == null || routeDetour < 0
          || Math.abs(routeDetour - Math.max(0, routePath - routeDirect)) > 1) {
        throw new BadRequestException("Google route direction must be checked again before booking.");
      }
      if (routeDetour > MAX_ROUTE_DETOUR_MINUTES
          && ((double) routePath / (double) routeDirect) > MAX_ROUTE_DETOUR_RATIO) {
        throw new BadRequestException("This booking would make the inspector route backtrack too far.");
      }
    }
  }

  private Integer integerValue(Object value) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String && StringUtils.hasText((String) value)) {
      try {
        return Integer.valueOf(((String) value).trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private int estimatedTravelMinutes(InspectorProfile profile, String area, String state) {
    if (inspectorMatchesArea(profile, area) && inspectorMatchesState(profile, state)
        && StringUtils.hasText(area) && !"Any area".equalsIgnoreCase(area)) {
      return 15;
    }
    if (inspectorMatchesState(profile, state) && StringUtils.hasText(state)) {
      return 30;
    }
    return 45;
  }

  private String recommendationReason(InspectorProfile profile, String area, String state, int workload) {
    List<String> reasons = new ArrayList<String>();
    if (StringUtils.hasText(area) && !"Any area".equalsIgnoreCase(area) && inspectorMatchesArea(profile, area)) {
      reasons.add("matches selected area");
    }
    if (StringUtils.hasText(state) && inspectorMatchesState(profile, state)) {
      reasons.add("serves " + state);
    }
    reasons.add(workload == 0 ? "route has no appointments yet" : workload + " existing appointment(s)");
    return String.join("; ", reasons);
  }

  private boolean inspectorMatchesState(InspectorProfile profile, String state) {
    if (!StringUtils.hasText(state)) {
      return true;
    }
    String base = profile.base.toUpperCase(Locale.ENGLISH);
    boolean knownState = false;
    for (String item : AUSTRALIAN_STATES) {
      if (base.contains(" " + item) || base.endsWith(item)) {
        knownState = true;
        break;
      }
    }
    // 导入数据没有州信息时不误删候选 Inspector，仍允许用户按区域继续筛选。
    if (!knownState) {
      return true;
    }
    return base.contains(state.toUpperCase(Locale.ENGLISH));
  }

  private String resolveBookingArea(String selectedArea, String address) {
    String explicitArea = trim(selectedArea);
    if (StringUtils.hasText(explicitArea) && !"Any area".equalsIgnoreCase(explicitArea)) {
      return explicitArea;
    }
    String value = trim(address).toLowerCase(Locale.ENGLISH);
    if (containsAny(value, "williamstown", "point cook", "tarneit", "truganina", "werribee",
        "hoppers crossing", "altona", "newport", "footscray", "yarraville", "sunshine",
        "st albans", "laverton", "wyndham vale", "manor lakes", "melton")) {
      return "West";
    }
    if (containsAny(value, "springvale", "clayton", "glen waverley", "oakleigh", "mulgrave",
        "dandenong", "noble park", "keysborough", "chadstone", "carnegie", "murrumbeena",
        "cheltenham", "mentone", "moorabbin", "berwick", "narre warren", "cranbourne", "pakenham")) {
      return "South East";
    }
    if (containsAny(value, "bayswater", "boronia", "ringwood", "croydon", "mitcham",
        "nunawading", "box hill", "doncaster", "templestowe", "vermont", "wantirna",
        "ferntree gully", "rowville", "lilydale", "mooroolbark", "camberwell", "hawthorn")) {
      return "East";
    }
    String postcodeText = value.replaceAll(".*\\b(\\d{4})\\b.*", "$1");
    if (postcodeText.matches("\\d{4}")) {
      int postcode = Integer.parseInt(postcodeText);
      if ((postcode >= 3011 && postcode <= 3038) || (postcode >= 3335 && postcode <= 3340)) {
        return "West";
      }
      if ((postcode >= 3160 && postcode <= 3207) || (postcode >= 3802 && postcode <= 3810)
          || postcode == 3145 || (postcode >= 3147 && postcode <= 3152)) {
        return "South East";
      }
      if (postcode >= 3101 && postcode <= 3156) {
        return "East";
      }
    }
    return "Any area";
  }

  private boolean containsAny(String source, String... values) {
    for (String value : values) {
      if (source.contains(value)) {
        return true;
      }
    }
    return false;
  }

  private void ensureInspectorServesAddress(String inspector, String address) {
    String matchedArea = resolveBookingArea("Any area", address);
    for (InspectorProfile profile : bookInspectorProfiles()) {
      if (!profile.name.equalsIgnoreCase(inspector)) {
        continue;
      }
      if (!"Any area".equalsIgnoreCase(matchedArea) && !inspectorMatchesArea(profile, matchedArea)) {
        throw new BadRequestException(
            profile.name + " covers " + profile.area + " and cannot take this " + matchedArea + " booking.");
      }
      return;
    }
    throw new BadRequestException("The selected inspector is not available for new bookings.");
  }

  private boolean inspectorMatchesArea(InspectorProfile profile, String area) {
    if (!StringUtils.hasText(area) || "Any area".equalsIgnoreCase(area)) {
      return true;
    }
    if ("All areas only".equalsIgnoreCase(area)) {
      return profile.area.toLowerCase(Locale.ENGLISH).contains("all area");
    }
    String target = area.toLowerCase(Locale.ENGLISH).replace("/", " ");
    target = target.trim().replaceAll("\\s+", " ");
    String profileArea = profile.area.toLowerCase(Locale.ENGLISH).replace("/", " ")
        .trim().replaceAll("\\s+", " ");
    if ("west".equals(target) || "east".equals(target) || "south east".equals(target)) {
      return profileArea.equals(target);
    }
    if ("east south east".equals(target)) {
      return "east".equals(profileArea) || "south east".equals(profileArea);
    }
    if ("north west".equals(target)) {
      return "west".equals(profileArea);
    }
    return profileArea.equals(target);
  }

  private List<Map<String, Object>> inspectorProfiles() {
    List<Map<String, Object>> profiles = new ArrayList<Map<String, Object>>();
    for (InspectorProfile profile : bookInspectorProfiles()) {
      Map<String, Object> item = new LinkedHashMap<String, Object>();
      item.put("name", profile.name);
      item.put("area", profile.area);
      item.put("base", profile.base);
      item.put("start", profile.start);
      item.put("end", profile.end);
      profiles.add(item);
    }
    return profiles;
  }

  private List<InspectorProfile> bookInspectorProfiles() {
    // Inspector 以当前数据库中的实际人员为准；默认配置只负责补充已知人员的区域/起点并在空库时兜底。
    Map<String, InspectorProfile> defaults = new LinkedHashMap<String, InspectorProfile>();
    for (InspectorProfile profile : DEFAULT_BOOK_INSPECTORS) {
      defaults.put(profile.name.toLowerCase(Locale.ENGLISH), profile);
    }
    List<InspectionRecord> records = inspectionMapper.selectList(new LambdaQueryWrapper<InspectionRecord>()
        .select(InspectionRecord::getInspector, InspectionRecord::getCityCouncil, InspectionRecord::getSuburb,
            InspectionRecord::getAddress, InspectionRecord::getUpdatedAt, InspectionRecord::getId)
        .isNotNull(InspectionRecord::getInspector)
        .ne(InspectionRecord::getInspector, "")
        .orderByDesc(InspectionRecord::getUpdatedAt)
        .orderByDesc(InspectionRecord::getId));
    Map<String, InspectorProfile> dynamic = new LinkedHashMap<String, InspectorProfile>();
    for (InspectionRecord record : records) {
      String name = trim(record.getInspector());
      String key = name.toLowerCase(Locale.ENGLISH);
      if (!StringUtils.hasText(name) || dynamic.containsKey(key)) {
        continue;
      }
      InspectorProfile configured = defaults.get(key);
      if (configured != null) {
        dynamic.put(key, configured);
        continue;
      }
      // Imported historical inspectors are not offered for new bookings.
    }
    for (InspectorProfile configured : DEFAULT_BOOK_INSPECTORS) {
      dynamic.putIfAbsent(configured.name.toLowerCase(Locale.ENGLISH), configured);
    }
    List<InspectorProfile> profiles = new ArrayList<InspectorProfile>(dynamic.values());
    profiles.sort(Comparator.comparing(profile -> profile.name.toLowerCase(Locale.ENGLISH)));
    return profiles;
  }

  private String firstText(String first, String second, String fallback) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    if (StringUtils.hasText(second)) {
      return second.trim();
    }
    return fallback;
  }

  private List<String> bookAreas() {
    return Arrays.asList("Any area", "North", "West", "East", "South East", "North/West",
        "East/South East", "All areas only");
  }

  private List<String> bookTimeSlots() {
    return Arrays.asList("Any hour", "09:00 - 10:00", "10:00 - 11:00", "11:00 - 12:00",
        "12:00 - 13:00", "13:00 - 14:00", "14:00 - 15:00", "15:00 - 16:00", "16:00 - 17:00");
  }

  private String selectedProductLabel(Object source) {
    List<String> products = new ArrayList<String>();
    if (source instanceof List) {
      for (Object item : (List<?>) source) {
        String value = trim(stringValue(item));
        if (StringUtils.hasText(value)
            && !containsIgnoreCase(BOOK_ATOMIC_PRODUCTS, value)
            && !"ALL PRODUCTS".equalsIgnoreCase(value)) {
          return "__invalid__";
        }
        if (StringUtils.hasText(value) && !containsIgnoreCase(products, value)) {
          products.add(value);
        }
      }
    } else {
      String value = trim(stringValue(source));
      if (StringUtils.hasText(value)) {
        products.add(value);
      }
    }
    if (products.isEmpty()) {
      return "";
    }
    if (containsIgnoreCase(products, "ALL PRODUCTS")) {
      return products.size() == 1 ? "ALL PRODUCTS" : "__invalid__";
    }
    if (products.size() == 1) {
      String single = products.get(0);
      return "HP".equalsIgnoreCase(single) || "Battery".equalsIgnoreCase(single) ? single + " only" : single;
    }
    List<String> ordered = new ArrayList<String>();
    for (String candidate : Arrays.asList("MAC", "DAC", "HP", "Battery", "SP")) {
      if (containsIgnoreCase(products, candidate)) {
        ordered.add(candidate);
      }
    }
    String label = String.join(" + ", ordered);
    if (BOOK_PRODUCTS.contains(label)) {
      return label;
    }
    if (containsIgnoreCase(products, "SP") && containsIgnoreCase(products, "Battery")
        && containsIgnoreCase(products, "MAC") && products.size() == 3) {
      return "SP + Battery + MAC";
    }
    if (containsIgnoreCase(products, "SP") && containsIgnoreCase(products, "Battery") && products.size() == 2) {
      return "SP + Battery";
    }
    if (containsIgnoreCase(products, "HP") && containsIgnoreCase(products, "Battery") && products.size() == 2) {
      return "HP + Battery";
    }
    return label;
  }

  private void validateBookProduct(String product) {
    if (!StringUtils.hasText(product)) {
      throw new BadRequestException("Choose at least one inspection product before finding slots.");
    }
    if (!BOOK_PRODUCTS.contains(product)) {
      throw new BadRequestException("The selected inspection product combination is not supported.");
    }
  }

  private void validateBookContact(String phone, String secondaryPhone) {
    if (!StringUtils.hasText(phone)) {
      throw new BadRequestException("Phone is required.");
    }
    if (!isAustralianPhone(phone)) {
      throw new BadRequestException("Enter a valid 10-digit Australian mobile or landline number.");
    }
    if (StringUtils.hasText(secondaryPhone) && !isAustralianPhone(secondaryPhone)) {
      throw new BadRequestException("Enter a valid Australian secondary phone number.");
    }
  }

  private boolean isAustralianPhone(String phone) {
    String digits = normalizeAustralianPhone(phone);
    return digits.matches("04\\d{8}")
        || digits.matches("0[2378]\\d{8}");
  }

  private String normalizeAustralianPhone(String phone) {
    String digits = trim(phone).replaceAll("[^0-9+]", "");
    if (digits.startsWith("+61")) {
      digits = "0" + digits.substring(3);
    } else if (digits.startsWith("61") && digits.length() >= 11) {
      digits = "0" + digits.substring(2);
    }
    digits = digits.replaceAll("\\D", "");
    if (digits.matches("[23478]\\d{8}")) {
      digits = "0" + digits;
    }
    return digits;
  }

  private String validateAustralianAddressAndState(String address, String state) {
    String normalizedAddress = trim(address);
    if (!StringUtils.hasText(normalizedAddress)) {
      throw new BadRequestException("Australian address is required before finding slots.");
    }
    // 接受 Google/CRM 常见的澳洲街道类型及缩写；例如 Circuit 经常只保存成 Cct。
    String streetType = "(street|st|road|rd|avenue|ave|drive|dr|lane|ln|court|ct|crescent|cres|circuit|cct|boulevard|blvd|highway|hwy|parade|pde|place|pl|way|terrace|tce|close|cl|grove|gr|gardens|gdns|green|grn|esplanade|esp|freeway|fwy|parkway|pkwy|square|sq|track|trk|walk|rise|mews|quay|loop|strand|vale|view|hill|ridge)";
    Pattern streetPattern = Pattern.compile("(?i)^(?:(?:unit|u|suite|shop|lot)\\s*[a-z0-9-]+[\\s,/-]+|[a-z0-9-]+/)?\\d+[a-z]?\\s+.+?\\b" + streetType + "\\b");
    Matcher streetMatcher = streetPattern.matcher(normalizedAddress);
    boolean hasStreet = streetMatcher.find();
    boolean hasPostcode = normalizedAddress.matches(".*\\b\\d{4}\\b.*");
    String locationText = hasStreet
        ? normalizedAddress.substring(streetMatcher.end())
            .replaceAll("(?i),?\\s*Australia\\s*$", "")
            .replaceAll("(?i)\\b(NSW|VIC|QLD|SA|WA|TAS|ACT|NT)\\b", "")
            .replaceAll("\\b\\d{4}\\b", "")
            .replaceAll("[\\s,]+", " ")
            .trim()
        : "";
    boolean hasSuburb = locationText.matches("(?i)^[a-z][a-z .'-]{1,}$");
    if (!hasStreet || (!hasPostcode && !hasSuburb)) {
      throw new BadRequestException("Enter a complete Australian address with a street number, street name/type, and suburb or postcode.");
    }
    String selectedState = trim(state).toUpperCase(Locale.ENGLISH);
    if (StringUtils.hasText(selectedState) && !AUSTRALIAN_STATES.contains(selectedState)) {
      throw new BadRequestException("Choose a valid Australian state.");
    }
    String addressState = stateFrom("", normalizedAddress);
    if (!StringUtils.hasText(addressState)) {
      addressState = selectedState;
    }
    if (!StringUtils.hasText(addressState)) {
      throw new BadRequestException("Choose the Australian state.");
    }
    if (StringUtils.hasText(selectedState) && !selectedState.equals(addressState)) {
      throw new BadRequestException("Selected state does not match the Australian address.");
    }
    return addressState;
  }

  private InspectionRecord rescheduleRecord(Long id) {
    InspectionRecord record = getRecord(id);
    if (!"Reschedule".equalsIgnoreCase(trim(record.getStatus()))) {
      throw new BadRequestException("The selected record is no longer in the reschedule pool.");
    }
    return record;
  }

  private void ensureBookAppointment(InspectionRecord record) {
    String status = trim(record == null ? null : record.getStatus());
    if (!"Fixed".equalsIgnoreCase(status) && !"Booked".equalsIgnoreCase(status)) {
      throw new BadRequestException("Only a confirmed Book appointment can be changed.");
    }
  }

  private String duplicateCustomerWarning(Map<String, Object> body, Long excludedId) {
    if (body == null) {
      return "";
    }
    Map<Long, InspectionRecord> matches = new LinkedHashMap<Long, InspectionRecord>();
    String macId = trim(stringValue(body.get("macId")));
    String phone = trim(stringValue(body.get("phoneNumber")));
    String address = trim(stringValue(body.get("address")));
    if (StringUtils.hasText(macId)) {
      addDuplicateMatches(matches, inspectionMapper.selectList(new LambdaQueryWrapper<InspectionRecord>()
          .eq(InspectionRecord::getMacId, macId)), excludedId);
    }
    if (StringUtils.hasText(phone)) {
      addDuplicateMatches(matches, inspectionMapper.selectList(new LambdaQueryWrapper<InspectionRecord>()
          .eq(InspectionRecord::getPhoneNumber, phone)), excludedId);
    }
    if (StringUtils.hasText(address)) {
      addDuplicateMatches(matches, inspectionMapper.selectList(new LambdaQueryWrapper<InspectionRecord>()
          .eq(InspectionRecord::getAddress, address)), excludedId);
    }
    if (matches.isEmpty()) {
      return "";
    }
    List<String> ids = new ArrayList<String>();
    for (Long id : matches.keySet()) {
      ids.add("#" + id);
      if (ids.size() >= 5) {
        break;
      }
    }
    return "Possible duplicate customer found in existing record(s) " + String.join(", ", ids)
        + ". You can continue booking after checking the details.";
  }

  private void addDuplicateMatches(Map<Long, InspectionRecord> target, List<InspectionRecord> records, Long excludedId) {
    for (InspectionRecord record : records) {
      if (record.getId() != null && !record.getId().equals(excludedId)) {
        target.put(record.getId(), record);
      }
    }
  }

  private boolean containsIgnoreCase(List<String> values, String target) {
    for (String value : values) {
      if (target.equalsIgnoreCase(value)) {
        return true;
      }
    }
    return false;
  }

  private String requiredString(Object value, String message) {
    String text = trim(stringValue(value));
    if (!StringUtils.hasText(text)) {
      throw new BadRequestException(message);
    }
    return text;
  }

  private String stateFrom(String state, String address) {
    if (StringUtils.hasText(state)) {
      return state.trim().toUpperCase(Locale.ENGLISH);
    }
    String upper = trim(address).toUpperCase(Locale.ENGLISH);
    for (String item : AUSTRALIAN_STATES) {
      if (Pattern.compile("(^|[^A-Z])" + item + "([^A-Z]|$)").matcher(upper).find()) {
        return item;
      }
    }
    return "";
  }

  private String inferSuburb(String address) {
    String text = trim(address);
    String[] parts = text.split(",");
    if (parts.length >= 2) {
      String candidate = parts[parts.length - 2].trim();
      String[] words = candidate.split("\\s+");
      if (words.length > 2) {
        return words[0] + " " + words[1];
      }
      return candidate;
    }
    return "";
  }

  private String firstName(String customerName) {
    String[] parts = trim(customerName).split("\\s+");
    return parts.length == 0 ? "" : parts[0];
  }

  private String lastName(String customerName) {
    String[] parts = trim(customerName).split("\\s+");
    if (parts.length <= 1) {
      return "";
    }
    List<String> rest = new ArrayList<String>();
    for (int index = 1; index < parts.length; index++) {
      rest.add(parts[index]);
    }
    return String.join(" ", rest);
  }

  private String bookingRawJson(Map<String, Object> body, int durationMinutes, CurrentUser currentUser,
                                String existingRawJson) {
    Map<String, Object> raw = rawJsonMap(existingRawJson);
    raw.putAll(body);
    raw.put("durationMinutes", durationMinutes);
    raw.put("source", "Book");
    String username = currentUser == null ? "system" : currentUser.getUsername();
    raw.putIfAbsent("createdBy", username);
    raw.put("fixed", true);
    raw.put("bookingStatus", "fixed");
    raw.put("fixedAt", LocalDateTime.now().toString());
    raw.put("fixedBy", username);
    return writeRawJson(raw);
  }

  private String mergeRawJson(String existingRawJson, Map<String, Object> updates) {
    Map<String, Object> raw = rawJsonMap(existingRawJson);
    raw.putAll(updates);
    return writeRawJson(raw);
  }

  private Map<String, Object> rawJsonMap(String existingRawJson) {
    Map<String, Object> raw = new LinkedHashMap<String, Object>();
    if (!StringUtils.hasText(existingRawJson)) {
      return raw;
    }
    try {
      Map<?, ?> existing = objectMapper.readValue(existingRawJson, Map.class);
      for (Map.Entry<?, ?> entry : existing.entrySet()) {
        if (entry.getKey() != null) {
          raw.put(String.valueOf(entry.getKey()), entry.getValue());
        }
      }
    } catch (Exception ex) {
      LOG.warn("Failed to parse Book rawJson; new appointment metadata will be saved error={}", ex.getMessage());
    }
    return raw;
  }

  private String writeRawJson(Map<String, Object> raw) {
    try {
      return objectMapper.writeValueAsString(raw);
    } catch (Exception ex) {
      return "{}";
    }
  }

  private void syncBookChange(InspectionRecord record) {
    // Google Sheet 只用于读取。预约变更保存在数据库，不发出任何 Sheet 写请求。
    LOG.info("Book changes saved to the application database; Google Sheet remains read-only id={} status={}",
        record == null ? null : record.getId(), record == null ? null : record.getStatus());
  }

  private Long longValue(Object value) {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String && StringUtils.hasText((String) value)) {
      try {
        return Long.valueOf(((String) value).trim());
      } catch (NumberFormatException ex) {
        throw new BadRequestException("Reschedule record id is invalid.");
      }
    }
    return null;
  }

  private int appointmentDuration(InspectionRecord record) {
    if (record == null) {
      return DEFAULT_BOOK_DURATION_MINUTES;
    }
    Integer explicitDuration = record.getWorkDurationMinutes();
    if (explicitDuration != null && explicitDuration > 0) {
      return explicitDuration;
    }
    // 应用内预约把准确工时保存在 rawJson；旧数据或非标准产品名称仍应优先使用该值。
    if (StringUtils.hasText(record.getRawJson())) {
      try {
        Map<?, ?> raw = objectMapper.readValue(record.getRawJson(), Map.class);
        Integer storedDuration = integerValue(raw.get("durationMinutes"));
        if (storedDuration != null && storedDuration > 0) {
          return storedDuration;
        }
      } catch (Exception ex) {
        LOG.debug("Unable to read stored appointment duration id={} error={}", record.getId(), ex.getMessage());
      }
    }
    return workDurationMinutes(record.getProjects());
  }

  private int workDurationMinutes(String projects) {
    Integer routeDuration = routeWorkDurationMinutes(projects);
    return routeDuration == null ? DEFAULT_BOOK_DURATION_MINUTES : routeDuration;
  }

  private Integer routeWorkDurationMinutes(String projects) {
    String label = trim(projects);
    if (!StringUtils.hasText(label)) {
      return null;
    }
    if ("ALL PRODUCTS".equalsIgnoreCase(label)) {
      return DEFAULT_BOOK_DURATION_MINUTES;
    }
    List<String> products = new ArrayList<String>();
    for (String part : label.split("\\+")) {
      String product = trim(part).replaceFirst("(?i)\\s+only$", "");
      if (!StringUtils.hasText(product)
          || !containsIgnoreCase(BOOK_ATOMIC_PRODUCTS, product)
          || containsIgnoreCase(products, product)) {
        return null;
      }
      products.add(product);
    }
    if (products.size() == 1) {
      return SINGLE_PRODUCT_DURATION_MINUTES;
    }
    if (products.size() == 2) {
      return DOUBLE_PRODUCT_DURATION_MINUTES;
    }
    return DEFAULT_BOOK_DURATION_MINUTES;
  }

  private String timeSlotStart(String timeSlot) {
    String text = trim(timeSlot);
    if (!StringUtils.hasText(text) || "Any hour".equalsIgnoreCase(text)) {
      return "";
    }
    int index = text.indexOf("-");
    return normalizeTimeLabel(index > 0 ? text.substring(0, index).trim() : text);
  }

  private String normalizeTimeLabel(String value) {
    int minutes = minutesOf(value);
    return minutes < 0 ? "" : timeLabel(minutes);
  }

  private int minutesOf(String value) {
    int seconds = secondsOfDay(value);
    return seconds < 0 ? -1 : seconds / 60;
  }

  private int secondsOfDay(String value) {
    String text = trim(value).toUpperCase(Locale.ENGLISH).replace(".", "");
    if (!StringUtils.hasText(text)) {
      return -1;
    }
    boolean pm = text.endsWith("PM");
    boolean am = text.endsWith("AM");
    if (pm || am) {
      text = text.substring(0, text.length() - 2).trim();
    }
    if (text.contains("AM") || text.contains("PM")) {
      return -1;
    }
    String[] parts = text.split(":", -1);
    try {
      if (parts.length < 1 || parts.length > 3 || !StringUtils.hasText(parts[0])) {
        return -1;
      }
      int hour = Integer.parseInt(parts[0].trim());
      int minute = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
      int second = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
      if (minute < 0 || minute > 59 || second < 0 || second > 59) {
        return -1;
      }
      if ((am || pm) && (hour < 1 || hour > 12)) {
        return -1;
      }
      if (!am && !pm && (hour < 0 || hour > 23)) {
        return -1;
      }
      if (pm && hour < 12) hour += 12;
      if (am && hour == 12) hour = 0;
      return hour * 60 * 60 + minute * 60 + second;
    } catch (Exception ex) {
      return -1;
    }
  }

  private String timeLabel(int minutes) {
    if (minutes < 0) {
      return "";
    }
    int hour = minutes / 60;
    int minute = minutes % 60;
    return String.format(Locale.ENGLISH, "%02d:%02d", hour, minute);
  }

  private static class InspectorProfile {
    private final String name;
    private final String area;
    private final String base;
    private final String start;
    private final String end;

    private InspectorProfile(String name, String area, String base, String start, String end) {
      this.name = name;
      this.area = area;
      this.base = base;
      this.start = start;
      this.end = end;
    }
  }

  private static class BusySlot {
    private final int start;
    private final int durationMinutes;

    private BusySlot(int start, int durationMinutes) {
      this.start = start;
      this.durationMinutes = durationMinutes;
    }
  }

  private Long createImportBatch(String fileName, String fileType, int totalRows, int successRows, int failedRows, CurrentUser currentUser) {
    // 每次上传都会生成导入批次，方便追踪文件来源、导入人和成功/失败数量。
    ImportBatch batch = new ImportBatch();
    batch.setFileName(fileName);
    batch.setFileType(fileType);
    batch.setTotalRows(totalRows);
    batch.setSuccessRows(successRows);
    batch.setFailedRows(failedRows);
    batch.setCreatedBy(currentUser == null ? "system" : currentUser.getUsername());
    importBatchMapper.insert(batch);
    return batch.getId();
  }

  private void updateImportBatch(Long batchId, ImportResult result) {
    // Google Sheet 同步结束后再次回写批次统计，确保最终结果与前端提示一致。
    ImportBatch batch = new ImportBatch();
    batch.setId(batchId);
    batch.setTotalRows(result.getTotalRows());
    batch.setSuccessRows(result.getSuccessRows());
    batch.setFailedRows(result.getFailedRows());
    importBatchMapper.updateById(batch);
  }

  private void insertRecords(List<InspectionRecord> records) {
    // 多行导入一次写入，避免远程 MySQL 为每一行产生一次网络往返。
    if (records == null || records.isEmpty()) {
      return;
    }
    if (records.size() == 1) {
      inspectionMapper.insert(records.get(0));
    } else {
      inspectionMapper.insertBatch(records);
    }
  }

  private LocalDate parseDate(String value) {
    // 兼容 yyyy-M-d、d-M-yyyy、17-July 等常见导入日期格式，无法识别时返回 null。
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim().replace('.', '-').replace('/', '-');
    DateTimeFormatter[] fullFormats = new DateTimeFormatter[] {
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("d-M-yyyy")
    };
    for (DateTimeFormatter formatter : fullFormats) {
      try {
        return LocalDate.parse(normalized, formatter);
      } catch (DateTimeParseException ex) {
        // Try next.
      }
    }
    DateTimeFormatter[] monthDayFormats = new DateTimeFormatter[] {
        DateTimeFormatter.ofPattern("d-MMM", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d-MMMM", Locale.ENGLISH)
    };
    for (DateTimeFormatter formatter : monthDayFormats) {
      try {
        MonthDay monthDay = MonthDay.from(formatter.parse(normalized));
        return LocalDate.of(Year.now().getValue(), monthDay.getMonthValue(), monthDay.getDayOfMonth());
      } catch (DateTimeParseException ex) {
        // Try next.
      }
    }
    return null;
  }

  private String value(Map<String, String> values, String key) {
    String value = values.get(key);
    return value == null ? "" : value;
  }

  private String normalizeHeader(String header) {
    return header == null ? "" : header.replace("\uFEFF", "").toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
  }

  private String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private String extension(String fileName) {
    int index = fileName.lastIndexOf('.');
    return index < 0 ? "" : fileName.substring(index + 1).toLowerCase(Locale.ENGLISH);
  }
}
