package com.smartuser.schedule.controller;

import com.smartuser.schedule.common.ApiResponse;
import com.smartuser.schedule.config.AuthInterceptor;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.model.GoogleSheetSyncResult;
import com.smartuser.schedule.model.ImportResult;
import com.smartuser.schedule.model.InspectionRecord;
import com.smartuser.schedule.service.GoogleSheetSyncService;
import com.smartuser.schedule.service.InspectionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 巡检导入与列表数据控制器。
 *
 * 功能作用：
 * 1. 提供 CSV/Excel 导入、Google Sheet 连接检查、导入记录分页查询、详情、编辑、取消、删除、批量删除、导出等接口。
 * 2. 查询条件与前端 Inspection Import 页面保持一致，支持日期、Inspector、Sales、关键字分页筛选。
 * 3. 所有导入解析、数据库写入、Google Sheet 同步、Excel 导出等业务逻辑都放在 InspectionService 中实现。
 */
@RestController
@RequestMapping("/api/inspections")
public class InspectionController {
  private final InspectionService inspectionService;
  private final GoogleSheetSyncService googleSheetSyncService;

  public InspectionController(InspectionService inspectionService, GoogleSheetSyncService googleSheetSyncService) {
    this.inspectionService = inspectionService;
    this.googleSheetSyncService = googleSheetSyncService;
  }

  /**
   * 文件导入接口：接收 multipart 上传的 CSV/Excel 文件，解析后写入数据库并尝试同步 Google Sheet。
   */
  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<ImportResult> importFile(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
    CurrentUser currentUser = (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    return ApiResponse.ok(inspectionService.importFile(file, currentUser));
  }

  /**
   * Google Sheet 状态检查接口：用于前端 Check Google Sheet 按钮确认同步配置和网络是否可用。
   */
  @GetMapping("/google-sheet/status")
  public ApiResponse<GoogleSheetSyncResult> googleSheetStatus() {
    return ApiResponse.ok(googleSheetSyncService.checkStatus());
  }

  @GetMapping("/google-sheet/sheets")
  public ApiResponse<List<Map<String, Object>>> googleSheetTabs() {
    return ApiResponse.ok(googleSheetSyncService.listImportSheets());
  }

  @GetMapping("/google-sheet/sheets/{sheetGid}/preview")
  public ApiResponse<Map<String, Object>> previewGoogleSheetTab(@PathVariable Long sheetGid) {
    return ApiResponse.ok(googleSheetSyncService.previewImportSheet(sheetGid));
  }

  @PostMapping("/google-sheet/sheets/{sheetGid}/import")
  public ApiResponse<Map<String, Object>> importGoogleSheetTab(@PathVariable Long sheetGid,
                                                               @RequestBody(required = false) Map<String, Object> body,
                                                               HttpServletRequest request) {
    boolean replaceExisting = body != null && Boolean.TRUE.equals(body.get("replaceExisting"));
    CurrentUser currentUser = (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    return ApiResponse.ok(inspectionService.importGoogleSheetWeek(sheetGid, replaceExisting, currentUser));
  }

  /**
   * 导入记录分页查询接口：支持日期范围、Inspector、Sales、关键字筛选，默认每页 10 条。
   */
  @GetMapping("/records")
  public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String startDate,
                                               @RequestParam(required = false) String endDate,
                                               @RequestParam(required = false) String inspector,
                                               @RequestParam(required = false) String sales,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size) {
    return ApiResponse.ok(inspectionService.listRecords(startDate, endDate, inspector, sales, keyword, page, size));
  }

  /**
   * 导入记录导出接口：不传 ids 时按当前查询条件导出全部，传 ids 时只导出选中的记录，返回表头加粗的 Excel 文件。
   */
  @PostMapping(value = "/records/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  public ResponseEntity<byte[]> export(@RequestBody(required = false) Map<String, Object> body) {
    String startDate = stringValue(body, "startDate");
    String endDate = stringValue(body, "endDate");
    String inspector = stringValue(body, "inspector");
    String sales = stringValue(body, "sales");
    String keyword = stringValue(body, "keyword");
    byte[] excel = inspectionService.exportRecords(startDate, endDate, inspector, sales, keyword, idsValue(body));
    return excelResponse(excel, "inspection-records.xlsx");
  }

  /**
   * 导入记录详情接口：用于前端查看完整导入字段和原始业务信息。
   */
  @GetMapping("/records/{id}")
  public ApiResponse<InspectionRecord> detail(@PathVariable Long id) {
    return ApiResponse.ok(inspectionService.getRecord(id));
  }

  /**
   * 导入记录编辑接口：保存前端编辑后的巡检字段，例如 Inspector、日期、时间、产品、备注等。
   */
  @PutMapping("/records/{id}")
  public ApiResponse<InspectionRecord> update(@PathVariable Long id, @RequestBody InspectionRecord record) {
    return ApiResponse.ok(inspectionService.updateRecord(id, record));
  }

  /**
   * 取消记录接口：把指定巡检记录状态标记为 Cancelled，不物理删除数据。
   */
  @PutMapping("/records/{id}/cancel")
  public ApiResponse<InspectionRecord> cancel(@PathVariable Long id) {
    return ApiResponse.ok(inspectionService.cancelRecord(id));
  }

  /**
   * 按查询条件删除接口：前端勾选“删除全部”时，按当前列表筛选条件删除匹配的全部数据。
   */
  @DeleteMapping("/records/query")
  public ApiResponse<Integer> deleteByQuery(@RequestBody Map<String, String> body) {
    String startDate = body == null ? null : body.get("startDate");
    String endDate = body == null ? null : body.get("endDate");
    String inspector = body == null ? null : body.get("inspector");
    String sales = body == null ? null : body.get("sales");
    String keyword = body == null ? null : body.get("keyword");
    String route = body == null ? null : body.get("route");
    if (route != null) {
      return ApiResponse.ok(inspectionService.deleteRecordsByRouteFilter(startDate, endDate, route, inspector, sales,
          keyword));
    }
    return ApiResponse.ok(inspectionService.deleteRecordsByFilter(startDate, endDate, inspector, sales, keyword));
  }

  /**
   * 单条删除接口：根据记录 id 物理删除一条导入数据。
   */
  @DeleteMapping("/records/{id}")
  public ApiResponse<Integer> delete(@PathVariable Long id) {
    return ApiResponse.ok(inspectionService.deleteRecord(id));
  }

  /**
   * 批量删除接口：删除前端手动勾选的记录 id 列表。
   */
  @DeleteMapping("/records/batch")
  public ApiResponse<Integer> batchDelete(@RequestBody Map<String, List<Long>> body) {
    List<Long> ids = body == null ? null : body.get("ids");
    return ApiResponse.ok(inspectionService.deleteRecords(ids));
  }

  private ResponseEntity<byte[]> excelResponse(byte[] excel, String fileName) {
    // Excel 响应统一设置下载文件名和 xlsx 类型，导出的表头样式可在 Excel 中保留。
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(excel);
  }

  private String stringValue(Map<String, Object> body, String key) {
    Object value = body == null ? null : body.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private List<Long> idsValue(Map<String, Object> body) {
    Object source = body == null ? null : body.get("ids");
    List<Long> ids = new ArrayList<Long>();
    if (!(source instanceof List)) {
      return ids;
    }
    for (Object item : (List<?>) source) {
      Long id = longValue(item);
      if (id != null && !ids.contains(id)) {
        ids.add(id);
      }
    }
    return ids;
  }

  private Long longValue(Object value) {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        return Long.valueOf((String) value);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }
}
