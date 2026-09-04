package com.smartuser.schedule.controller;

import com.smartuser.schedule.common.ApiResponse;
import com.smartuser.schedule.config.AuthInterceptor;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.service.InspectorWorkspaceService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/inspector")
public class InspectorWorkspaceController {
  private final InspectorWorkspaceService service;

  public InspectorWorkspaceController(InspectorWorkspaceService service) { this.service = service; }

  @GetMapping("/route")
  public ApiResponse<Map<String, Object>> route(@RequestParam(required = false) String date, HttpServletRequest request) {
    LocalDate selected = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
    return ApiResponse.ok(service.route(user(request), selected));
  }

  @GetMapping("/photo-review")
  public ApiResponse<Map<String, Object>> photoReview(@RequestParam(required = false) String inspector,
                                                       @RequestParam(required = false) String date,
                                                       HttpServletRequest request) {
    LocalDate selected = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
    return ApiResponse.ok(service.photoReview(user(request), inspector, selected));
  }

  @GetMapping("/weekly-confirmations")
  public ApiResponse<Map<String, Object>> weeklyConfirmations(@RequestParam(required = false) String weekStart,
                                                               HttpServletRequest request) {
    LocalDate selected = weekStart == null || weekStart.isBlank() ? LocalDate.now() : LocalDate.parse(weekStart);
    return ApiResponse.ok(service.weeklyConfirmations(user(request), selected));
  }

  @PutMapping("/weekly-confirmations/{id}")
  public ApiResponse<Map<String, Object>> confirmInspection(@PathVariable long id,
                                                            @RequestBody Map<String, Object> body,
                                                            HttpServletRequest request) {
    boolean confirmed = Boolean.parseBoolean(string(body.get("confirmed")));
    service.confirmInspection(user(request), id, confirmed);
    return ApiResponse.ok(Collections.singletonMap("confirmed", confirmed));
  }

  @PutMapping("/route/{id}/status")
  public ApiResponse<Map<String, Object>> status(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
    service.updateStatus(user(request), id, String.valueOf(body.getOrDefault("status", "")), string(body.get("reason")));
    return ApiResponse.ok(Collections.singletonMap("updated", true));
  }

  @PutMapping("/route/{id}/details")
  public ApiResponse<Map<String, Object>> details(@PathVariable long id, @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
    service.saveDetails(user(request), id, string(body.get("email")), string(body.get("remark")));
    return ApiResponse.ok(Collections.singletonMap("updated", true));
  }

  @GetMapping("/shifts")
  public ApiResponse<Map<String, Object>> shifts(HttpServletRequest request) {
    return ApiResponse.ok(service.shifts(user(request)));
  }

  @PostMapping("/shifts")
  public ApiResponse<Map<String, Object>> shift(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    service.submitShift(user(request), LocalDate.parse(string(body.get("date"))),
        Boolean.parseBoolean(string(body.get("working"))), string(body.get("start")), string(body.get("end")));
    return ApiResponse.ok(Collections.singletonMap("submitted", true));
  }

  @GetMapping("/shifts/week")
  public ApiResponse<Map<String, Object>> managerShifts(@RequestParam String start, HttpServletRequest request) {
    return ApiResponse.ok(service.managerShifts(user(request), LocalDate.parse(start)));
  }

  @PutMapping("/shifts/manage")
  public ApiResponse<Map<String, Object>> managerShift(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    service.managerSaveShift(user(request), string(body.get("inspectorName")), LocalDate.parse(string(body.get("date"))),
        Boolean.parseBoolean(string(body.get("working"))), string(body.get("start")), string(body.get("end")));
    return ApiResponse.ok(Collections.singletonMap("updated", true));
  }

  @PostMapping(value = "/route/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<Map<String, Object>> upload(@PathVariable long id,
                                                 @RequestPart("categoryKey") String categoryKey,
                                                 @RequestPart("file") MultipartFile file,
                                                 HttpServletRequest request) throws Exception {
    return ApiResponse.ok(service.upload(user(request), id, categoryKey, file));
  }

  @GetMapping("/photos/{id}")
  public ResponseEntity<?> photo(@PathVariable long id, HttpServletRequest request) {
    InspectorWorkspaceService.PhotoDownload photo = service.photo(user(request), id);
    MediaType type;
    try { type = MediaType.parseMediaType(photo.contentType); } catch (Exception ignored) { type = MediaType.APPLICATION_OCTET_STREAM; }
    return ResponseEntity.ok()
        .contentType(type)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
            .filename(photo.originalName, StandardCharsets.UTF_8).build().toString())
        .body(photo.resource);
  }

  @DeleteMapping("/photos/{id}")
  public ApiResponse<Map<String, Object>> deletePhoto(@PathVariable long id, HttpServletRequest request) throws Exception {
    service.deletePhoto(user(request), id);
    return ApiResponse.ok(Collections.singletonMap("deleted", true));
  }

  @PutMapping("/photos/{id}/remark")
  public ApiResponse<Map<String, Object>> photoRemark(@PathVariable long id,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
    service.updatePhotoRemark(user(request), id, string(body.get("remark")));
    return ApiResponse.ok(Collections.singletonMap("updated", true));
  }

  private CurrentUser user(HttpServletRequest request) {
    return (CurrentUser) request.getAttribute(AuthInterceptor.CURRENT_USER_ATTRIBUTE);
  }
  private String string(Object value) { return value == null ? "" : String.valueOf(value); }
}
