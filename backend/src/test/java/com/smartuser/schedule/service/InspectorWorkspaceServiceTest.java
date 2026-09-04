package com.smartuser.schedule.service;

import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.model.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class InspectorWorkspaceServiceTest {

  @Test
  void photoReviewRejectsSchedulerAccounts(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser scheduler = new CurrentUser();
    scheduler.setRoleCode("scheduler");
    scheduler.setRealName("Dylan");

    assertThatThrownBy(() -> service.photoReview(scheduler, "Dylan", LocalDate.of(2026, 8, 21)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Manager access");
    verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class));
  }

  @Test
  void weeklyConfirmationRejectsNonManagerAccounts(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setRoleCode("inspector");

    assertThatThrownBy(() -> service.weeklyConfirmations(inspector, LocalDate.of(2026, 9, 2)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Manager access");
  }

  @Test
  void managerCanConfirmCompletedInspection(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("SELECT field_status"), eq(88L)))
        .thenReturn(List.of(Map.of("field_status", "done")));
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("manager_confirmed = 1"), eq("Conan Manager"), eq(88L)))
        .thenReturn(1);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser manager = new CurrentUser();
    manager.setRoleCode("manager");
    manager.setRealName("Conan Manager");

    service.confirmInspection(manager, 88L, true);

    verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("manager_confirmed = 1"),
        eq("Conan Manager"), eq(88L));
  }

  @Test
  void managerCannotConfirmPendingInspection(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.contains("SELECT field_status"), eq(88L)))
        .thenReturn(List.of(Map.of("field_status", "fixed")));
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser manager = new CurrentUser();
    manager.setRoleCode("admin");

    assertThatThrownBy(() -> service.confirmInspection(manager, 88L, true))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Done or Customer unavailable");
    verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.contains("manager_confirmed = 1"), any(), any());
  }

  @Test
  void customerUnavailableRequiresAReason(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setRealName("Dylan");

    assertThatThrownBy(() -> service.updateStatus(inspector, 88L, "customer_unavailable", "  "))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("reason");
    verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void customerUnavailableSavesStatusAndReasonTogether(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setRealName("Dylan");

    service.updateStatus(inspector, 88L, "customer_unavailable", "No answer at the door");

    verify(jdbcTemplate).update(
        org.mockito.ArgumentMatchers.contains("inspector_remark"),
        eq("customer_unavailable"), eq("No answer at the door"), eq(88L));
  }

  @Test
  void jobDoneRequiresAllNineInspectionPhotos(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(88L)))
        .thenReturn(List.of("switchboard"));
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setRealName("Dylan");

    assertThatThrownBy(() -> service.updateStatus(inspector, 88L, "done", null))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("required inspection media")
        .hasMessageContaining("Condenser Outdoor Location");
  }

  @Test
  void jobDoneSucceedsWhenAllNineInspectionPhotosExist(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(88L))).thenReturn(List.of(
        "switchboard", "switchboard_main_switch", "ducted_gas_vents", "premises_roof",
        "indoor_location_1", "indoor_location_2", "indoor_location_3", "indoor_location_4",
        "condenser_outdoor_location"));
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setRealName("Dylan");

    service.updateStatus(inspector, 88L, "done", null);

    verify(jdbcTemplate).update(anyString(), eq("done"), eq(88L));
  }

  @Test
  void batteryJobRequiresLocationFloorPlanAndVideo(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.contains("projects"), eq(String.class), eq(88L)))
        .thenReturn("Battery + SP");
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(88L)))
        .thenReturn(List.of("battery_location"));
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setRealName("Dylan");

    assertThatThrownBy(() -> service.updateStatus(inspector, 88L, "done", null))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Floor Plan")
        .hasMessageContaining("Measurements")
        .hasMessageContaining("Indoor / Outdoor Area Video")
        .hasMessageNotContaining("Battery location photo");
  }

  @Test
  void batteryJobAcceptsEitherIndoorOrOutdoorLocation(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.contains("projects"), eq(String.class), eq(88L)))
        .thenReturn("Battery");
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(88L))).thenReturn(List.of(
        "battery_serial_label", "drawn_floor_plan_measurements", "battery_measurements",
        "battery_indoor_outdoor_video"));
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setRealName("Dylan");

    service.updateStatus(inspector, 88L, "done", null);

    verify(jdbcTemplate).update(anyString(), eq("done"), eq(88L));
  }

  @Test
  void inspectionVideoRejectsFilesOverThreeGigabytes(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    MultipartFile file = mock(MultipartFile.class);
    when(file.isEmpty()).thenReturn(false);
    when(file.getSize()).thenReturn(3L * 1024 * 1024 * 1024 + 1);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setRealName("Dylan");

    assertThatThrownBy(() -> service.upload(inspector, 88L, "inspection_video_1", file))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("3 GB");
  }

  @Test
  void uploadedMediaKeepsATypeSafeExtension(@TempDir Path uploadDir) {
    InspectorWorkspaceService service = new InspectorWorkspaceService(mock(JdbcTemplate.class), uploadDir.toString());

    assertThat(service.safeExtension("walkthrough.mp4", "video/mp4", true)).isEqualTo(".mp4");
    assertThat(service.safeExtension("phone-upload", "video/quicktime", true)).isEqualTo(".mov");
    assertThat(service.safeExtension("switchboard.heif", "image/heif", false)).isEqualTo(".heif");
    assertThat(service.safeExtension("unsafe.exe", "image/png", false)).isEqualTo(".png");
  }

  @Test
  void inspectorCannotDeleteUploadFromAnUnassignedInspection(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(anyString(), eq(501L))).thenReturn(List.of(Map.of(
        "inspection_id", 88L, "stored_name", "photo.jpg", "uploaded_by", 99L)));
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setId(20L);
    inspector.setRealName("Dylan");

    assertThatThrownBy(() -> service.deletePhoto(inspector, 501L))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("not assigned");
    verify(jdbcTemplate, never()).update(
        org.mockito.ArgumentMatchers.contains("DELETE FROM inspection_photo"),
        org.mockito.ArgumentMatchers.<Object[]>any());
  }

  @Test
  void inspectorCanDeleteOwnUploadAndStoredFile(@TempDir Path uploadDir) throws Exception {
    Path folder = Files.createDirectories(uploadDir.resolve("88"));
    Path storedFile = Files.writeString(folder.resolve("photo.jpg"), "photo");
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(anyString(), eq(501L))).thenReturn(List.of(Map.of(
        "inspection_id", 88L, "stored_name", "photo.jpg", "uploaded_by", 20L)));
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("DELETE FROM inspection_photo"), eq(501L))).thenReturn(1);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setId(20L);
    inspector.setRealName("Dylan");

    service.deletePhoto(inspector, 501L);

    assertThat(storedFile).doesNotExist();
    verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("DELETE FROM inspection_photo"), eq(501L));
  }

  @Test
  void inspectorCanSaveRemarkOnAssignedPhoto(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(anyString(), eq(501L)))
        .thenReturn(List.of(Map.of("inspection_id", 88L)));
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("SET remark"), eq("Outdoor unit is 1200 mm wide"), eq(501L)))
        .thenReturn(1);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setRealName("Dylan");

    service.updatePhotoRemark(inspector, 501L, "  Outdoor unit is 1200 mm wide  ");

    verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("SET remark"),
        eq("Outdoor unit is 1200 mm wide"), eq(501L));
  }

  @Test
  void managerCanSaveRemarkOnAnyReviewedMedia(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(anyString(), eq(501L)))
        .thenReturn(List.of(Map.of("inspection_id", 88L)));
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(88L))).thenReturn(1);
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.contains("SET remark"), eq("Reviewed by manager"), eq(501L)))
        .thenReturn(1);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser manager = new CurrentUser();
    manager.setRoleCode("manager");

    service.updatePhotoRemark(manager, 501L, "Reviewed by manager");

    verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains("SET remark"),
        eq("Reviewed by manager"), eq(501L));
    verify(jdbcTemplate, never()).queryForObject(
        org.mockito.ArgumentMatchers.contains("LOWER(TRIM(inspector))"), eq(Integer.class), any(), any());
  }

  @Test
  void photoRemarkRejectsTextOverTwoThousandCharacters(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(anyString(), eq(501L)))
        .thenReturn(List.of(Map.of("inspection_id", 88L)));
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser inspector = new CurrentUser();
    inspector.setRealName("Dylan");

    assertThatThrownBy(() -> service.updatePhotoRemark(inspector, 501L, "x".repeat(2001)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("2000");
    verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.contains("SET remark"), any(), any());
  }

  @Test
  void routeAcceptsImportedRowsWhoseNameIsStoredInFirstAndLastName(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(Collections.emptyList());
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser scheduler = new CurrentUser();
    scheduler.setId(20L);
    scheduler.setRealName("Dylan");
    scheduler.setRoleCode("scheduler");

    service.route(scheduler, LocalDate.of(2026, 8, 5));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class),
        eq(LocalDate.of(2026, 8, 5)), eq("Dylan"));
    org.assertj.core.api.Assertions.assertThat(sql.getValue())
        .contains("TRIM(first_name)")
        .contains("TRIM(last_name)")
        .contains("TRIM(mac_id)");
  }

  @Test
  void routeMapsJeffAccountToJeffLiSheetAssignments(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(Collections.emptyList());
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser scheduler = new CurrentUser();
    scheduler.setId(7L);
    scheduler.setRealName("Jeff");
    scheduler.setRoleCode("scheduler");

    service.route(scheduler, LocalDate.of(2026, 8, 5));

    verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
        eq(LocalDate.of(2026, 8, 5)), eq("Jeff Li"));
  }

  @Test
  void routeMapsKyleAccountToKyleNswSheetAssignments(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(Collections.emptyList());
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser scheduler = new CurrentUser();
    scheduler.setId(5L);
    scheduler.setRealName("Kyle");
    scheduler.setRoleCode("scheduler");

    service.route(scheduler, LocalDate.of(2026, 8, 5));

    verify(jdbcTemplate).query(anyString(), any(RowMapper.class),
        eq(LocalDate.of(2026, 8, 5)), eq("Kyle NSW"));
  }

  @Test
  void submittedShiftCannotBeChangedByScheduler(@TempDir Path uploadDir) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    InspectorWorkspaceService service = new InspectorWorkspaceService(jdbcTemplate, uploadDir.toString());
    CurrentUser scheduler = new CurrentUser();
    scheduler.setId(12L);
    scheduler.setRealName("Ronit");
    scheduler.setRoleCode("scheduler");
    LocalDate nextMonday = LocalDate.now().with(DayOfWeek.MONDAY).plusWeeks(1);

    assertThatThrownBy(() -> service.submitShift(scheduler, nextMonday, true, "09:00", "16:30"))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("already submitted");
    verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
  }
}
