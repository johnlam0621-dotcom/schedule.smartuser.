package com.smartuser.schedule.controller;

import com.smartuser.schedule.common.ApiResponse;
import com.smartuser.schedule.config.AuthInterceptor;
import com.smartuser.schedule.model.CurrentUser;
import com.smartuser.schedule.service.InspectorWorkspaceService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

  @GetMapping("/inspection-done/search")
  public ApiResponse<Map<String, Object>> inspectionDoneSearch(@RequestParam(required = false) String macId,
                                                                @RequestParam(required = false) String phone,
                                                                HttpServletRequest request) {
    return ApiResponse.ok(service.inspectionDoneSearch(user(request), macId, phone));
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

  @PostMapping(value = "/route/{id}/photos/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<Map<String, Object>> uploadChunk(@PathVariable long id,
                                                      @RequestPart("categoryKey") String categoryKey,
                                                      @RequestPart("uploadId") String uploadId,
                                                      @RequestPart("chunkIndex") String chunkIndex,
                                                      @RequestPart("totalChunks") String totalChunks,
                                                      @RequestPart("file") MultipartFile file,
                                                      HttpServletRequest request) throws Exception {
    return ApiResponse.ok(service.uploadChunk(user(request), id, categoryKey, uploadId,
        Integer.parseInt(chunkIndex), Integer.parseInt(totalChunks), file));
  }

  @PostMapping("/route/{id}/photos/complete")
  public ApiResponse<Map<String, Object>> completeChunkUpload(@PathVariable long id,
                                                               @RequestBody Map<String, Object> body,
                                                               HttpServletRequest request) throws Exception {
    return ApiResponse.ok(service.completeChunkUpload(user(request), id, string(body.get("categoryKey")),
        string(body.get("uploadId")), Integer.parseInt(string(body.get("totalChunks"))),
        Long.parseLong(string(body.get("totalSize"))), string(body.get("originalName")),
        string(body.get("contentType"))));
  }

  @GetMapping("/photos/{id}")
  public ResponseEntity<StreamingResponseBody> photo(@PathVariable long id,
                                                      @RequestParam(defaultValue = "false") boolean download,
                                                      HttpServletRequest request) {
    return mediaResponse(service.photo(user(request), id), download, request);
  }

  @PostMapping("/photos/{id}/playback/prepare")
  public ApiResponse<Map<String, Object>> prepareVideoPlayback(@PathVariable long id, HttpServletRequest request) {
    return ApiResponse.ok(service.prepareVideoPlayback(user(request), id));
  }

  @GetMapping("/photos/{id}/playback/status")
  public ApiResponse<Map<String, Object>> videoPlaybackStatus(@PathVariable long id, HttpServletRequest request) {
    return ApiResponse.ok(service.videoPlaybackStatus(user(request), id));
  }

  @GetMapping("/photos/{id}/playback")
  public ResponseEntity<StreamingResponseBody> videoPlayback(@PathVariable long id,
                                                              @RequestParam(defaultValue = "false") boolean download,
                                                              HttpServletRequest request) {
    return mediaResponse(service.videoPlayback(user(request), id), download, request);
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
  ResponseEntity<StreamingResponseBody> mediaResponse(InspectorWorkspaceService.PhotoDownload media, boolean download,
                                                       HttpServletRequest request) {
    MediaType type;
    try { type = MediaType.parseMediaType(media.contentType); } catch (Exception ignored) { type = MediaType.APPLICATION_OCTET_STREAM; }
    String disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
        .filename(media.originalName, StandardCharsets.UTF_8).build().toString();
    long contentLength;
    try {
      contentLength = media.resource.contentLength();
    } catch (IOException ex) {
      throw new IllegalStateException("Media file size could not be read.", ex);
    }
    String rangeHeader = request.getHeader(HttpHeaders.RANGE);
    if (StringUtils.hasText(rangeHeader)) {
      try {
        HttpRange range = HttpRange.parseRanges(rangeHeader).get(0);
        long start = range.getRangeStart(contentLength);
        long end = range.getRangeEnd(contentLength);
        long rangeLength = end - start + 1;
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + contentLength)
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .contentLength(rangeLength)
            .body(output -> copyMediaRange(media.resource.getInputStream(), output, start, rangeLength));
      } catch (Exception ignored) {
        // Invalid range: return the complete file below.
      }
    }
    return ResponseEntity.ok().contentType(type)
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
        .contentLength(contentLength)
        .body(output -> copyMediaRange(media.resource.getInputStream(), output, 0, contentLength));
  }

  private void copyMediaRange(InputStream source, OutputStream target, long start, long count) throws IOException {
    try (InputStream input = source) {
      input.skipNBytes(start);
      byte[] buffer = new byte[64 * 1024];
      long remaining = count;
      while (remaining > 0) {
        int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
        if (read < 0) break;
        target.write(buffer, 0, read);
        remaining -= read;
      }
    }
  }
  private String string(Object value) { return value == null ? "" : String.valueOf(value); }
}
