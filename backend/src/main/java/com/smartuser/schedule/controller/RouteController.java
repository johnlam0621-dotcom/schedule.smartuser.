package com.smartuser.schedule.controller;

import com.smartuser.schedule.common.ApiResponse;
import com.smartuser.schedule.config.AuthInterceptor;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.model.InspectionRecord;
import com.smartuser.schedule.service.InspectionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 路线地图控制器。
 *
 * 功能作用：
 * 1. 为 Routes map 页面提供路线筛选选项、路线汇总、分页列表和 Excel 导出接口。
 * 2. 路线当前按 Inspector 分组展示，未分配 Inspector 的记录统一归为 Unassigned。
 * 3. 具体查询、统计、分页和导出逻辑复用 InspectionService，保证与导入列表使用同一份巡检数据。
 */
@RestController
@RequestMapping("/api/routes")
public class RouteController {
  private final InspectionService inspectionService;

  public RouteController(InspectionService inspectionService) {
    this.inspectionService = inspectionService;
  }

  /**
   * 路线选项接口：按日期、Inspector、Sales、关键字统计可选路线，用于前端 Route 下拉框。
   */
  @GetMapping("/options")
  public ApiResponse<Map<String, Object>> options(@RequestParam(required = false) String startDate,
                                                  @RequestParam(required = false) String endDate,
                                                  @RequestParam(required = false) String inspector,
                                                  @RequestParam(required = false) String sales,
                                                  @RequestParam(required = false) String keyword) {
    return ApiResponse.ok(inspectionService.routeOptions(startDate, endDate, inspector, sales, keyword));
  }

  /**
   * 路线地图分页接口：按路线页查询条件返回汇总卡片、地图标记所需记录和下方路线记录列表。
   */
  @GetMapping("/map")
  public ApiResponse<Map<String, Object>> map(@RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(required = false) String route,
                                              @RequestParam(required = false) String inspector,
                                              @RequestParam(required = false) String sales,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size) {
    return ApiResponse.ok(inspectionService.routeMap(startDate, endDate, route, inspector, sales, keyword, page, size));
  }

  /**
   * Google 地图路线数据接口：返回当前查询范围内完整记录和 Inspector 基础区域，用于前端按时间线绘制连线。
   */
  @GetMapping("/map-data")
  public ApiResponse<Map<String, Object>> mapData(@RequestParam(required = false) String startDate,
                                                  @RequestParam(required = false) String endDate,
                                                  @RequestParam(required = false) String route,
                                                  @RequestParam(required = false) String inspector,
                                                  @RequestParam(required = false) String sales,
                                                  @RequestParam(required = false) String keyword) {
    return ApiResponse.ok(inspectionService.routeMapData(startDate, endDate, route, inspector, sales, keyword));
  }

  /**
   * Book 页面初始化接口：返回产品、Inspector、区域、时间段、当天已预约记录和可用时段统计。
   */
  @GetMapping("/book/overview")
  public ApiResponse<Map<String, Object>> bookOverview(@RequestParam(required = false) String date,
                                                       HttpServletRequest request) {
    CurrentUser currentUser = (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    return ApiResponse.ok(inspectionService.bookOverview(date, currentUser));
  }

  /**
   * Book 推荐时段接口：根据客户地址、州、区域、产品和日期计算可预约的 Inspector 时段。
   */
  @PostMapping("/book/slots")
  public ApiResponse<Map<String, Object>> bookSlots(@RequestBody(required = false) Map<String, Object> body,
                                                     HttpServletRequest request) {
    CurrentUser currentUser = (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    return ApiResponse.ok(inspectionService.bookSlots(body, currentUser));
  }

  /**
   * Book 保存预约接口：把选择的推荐时段写入巡检记录表，并参与 Routes map 后续排线。
   */
  @PostMapping("/book/appointments")
  public ApiResponse<InspectionRecord> createBookAppointment(@RequestBody Map<String, Object> body,
                                                             HttpServletRequest request) {
    CurrentUser currentUser = (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    return ApiResponse.ok(inspectionService.createBookAppointment(body, currentUser));
  }

  /**
   * Book 修改预约时间接口：校验新时段没有冲突并保留 Fixed 预约状态。
   */
  @PutMapping("/book/appointments/{id}/time")
  public ApiResponse<InspectionRecord> updateBookAppointmentTime(@PathVariable Long id,
                                                                 @RequestBody Map<String, Object> body,
                                                                 HttpServletRequest request) {
    CurrentUser currentUser = (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    return ApiResponse.ok(inspectionService.updateBookAppointmentTime(id, body, currentUser));
  }

  /**
   * Book 取消预约接口：使用 Cancelled 状态保留历史，不物理删除客户数据。
   */
  @PutMapping("/book/appointments/{id}/cancel")
  public ApiResponse<InspectionRecord> cancelBookAppointment(@PathVariable Long id,
                                                             HttpServletRequest request) {
    CurrentUser currentUser = (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
    return ApiResponse.ok(inspectionService.cancelBookAppointment(id, currentUser));
  }

  /**
   * Book MACID 查询接口：按 MACID 读取最近一条历史记录，前端可自动填充客户资料。
   */
  @GetMapping("/book/customer")
  public ApiResponse<Map<String, Object>> bookCustomer(@RequestParam String macId) {
    return ApiResponse.ok(inspectionService.findLatestCustomerByMacId(macId));
  }

  /**
   * 路线记录导出接口：支持按当前路线查询条件导出全部，也支持按前端勾选 ids 只导出选择数据，返回表头加粗的 Excel 文件。
   */
  @PostMapping(value = "/map/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  public ResponseEntity<byte[]> export(@RequestBody(required = false) Map<String, Object> body) {
    String startDate = stringValue(body, "startDate");
    String endDate = stringValue(body, "endDate");
    String route = stringValue(body, "route");
    String inspector = stringValue(body, "inspector");
    String sales = stringValue(body, "sales");
    String keyword = stringValue(body, "keyword");
    byte[] excel = inspectionService.exportRouteRecords(startDate, endDate, route, inspector, sales, keyword,
        idsValue(body));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"route-records.xlsx\"")
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(excel);
  }

  private String stringValue(Map<String, Object> body, String key) {
    // 导出接口 body 允许为空，统一把缺失值转换为 null，Service 层再决定是否加筛选条件。
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
