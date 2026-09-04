package com.smartuser.schedule.service;

import com.smartuser.schedule.config.GoogleSheetProperties;
import com.smartuser.schedule.model.CurrentUser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleSheetAutoImportServiceTest {
  @Test
  void importsOnlyNewCurrentOrFutureWeeksForManager() {
    GoogleSheetProperties properties = properties();
    GoogleSheetSyncService sheetService = mock(GoogleSheetSyncService.class);
    InspectionService inspectionService = mock(InspectionService.class);
    GoogleSheetAutoImportService service = new GoogleSheetAutoImportService(
        properties, sheetService, inspectionService);

    int currentWeek = 388 + (int) ChronoUnit.WEEKS.between(LocalDate.of(2026, 7, 27), LocalDate.now());
    long previousGid = currentWeek - 1L;
    long currentGid = currentWeek;
    long nextGid = currentWeek + 1L;
    String currentName = "Week " + currentWeek;
    String nextName = "Week " + (currentWeek + 1);

    when(sheetService.listImportSheets()).thenReturn(Arrays.asList(
        sheet(previousGid, "Week " + (currentWeek - 1)),
        sheet(currentGid, currentName),
        sheet(nextGid, nextName)));
    when(inspectionService.hasImportedGoogleSheetWeek(currentGid, currentName)).thenReturn(true);
    when(inspectionService.hasImportedGoogleSheetWeek(nextGid, nextName)).thenReturn(false);

    service.importNewWeeksAfterLogin(user("manager"));

    verify(inspectionService, never()).importGoogleSheetWeek(eq(previousGid), any(Boolean.class), any(CurrentUser.class));
    verify(inspectionService).mergeGoogleSheetWeek(eq(currentGid), any(CurrentUser.class));
    verify(inspectionService).importGoogleSheetWeek(eq(nextGid), eq(false), any(CurrentUser.class));
  }

  @Test
  void schedulerLoginNeverCallsGoogleSheet() {
    GoogleSheetSyncService sheetService = mock(GoogleSheetSyncService.class);
    InspectionService inspectionService = mock(InspectionService.class);
    GoogleSheetAutoImportService service = new GoogleSheetAutoImportService(
        properties(), sheetService, inspectionService);

    service.importNewWeeksAfterLogin(user("scheduler"));

    verify(sheetService, never()).listImportSheets();
    verify(inspectionService, never()).importGoogleSheetWeek(any(Long.class), any(Boolean.class), any(CurrentUser.class));
    verify(inspectionService, never()).mergeGoogleSheetWeek(any(Long.class), any(CurrentUser.class));
  }

  @Test
  void disabledAutomaticImportNeverCallsGoogleSheet() {
    GoogleSheetProperties properties = properties();
    properties.setAutoImportOnLogin(false);
    GoogleSheetSyncService sheetService = mock(GoogleSheetSyncService.class);
    GoogleSheetAutoImportService service = new GoogleSheetAutoImportService(
        properties, sheetService, mock(InspectionService.class));

    service.importNewWeeksAfterLogin(user("admin"));

    verify(sheetService, never()).listImportSheets();
  }

  @Test
  void legacyWriteSyncFlagDoesNotDisableReadOnlyAutomaticImport() {
    GoogleSheetProperties properties = properties();
    properties.setEnabled(false);
    GoogleSheetSyncService sheetService = mock(GoogleSheetSyncService.class);
    when(sheetService.listImportSheets()).thenReturn(Arrays.asList());
    GoogleSheetAutoImportService service = new GoogleSheetAutoImportService(
        properties, sheetService, mock(InspectionService.class));

    service.importNewWeeksAfterLogin(user("admin"));

    verify(sheetService).listImportSheets();
  }

  private GoogleSheetProperties properties() {
    GoogleSheetProperties properties = new GoogleSheetProperties();
    properties.setEnabled(true);
    properties.setAutoImportOnLogin(true);
    properties.setAutoImportMaxWeeks(8);
    return properties;
  }

  private CurrentUser user(String role) {
    CurrentUser user = new CurrentUser();
    user.setId(1L);
    user.setUsername(role + "-user");
    user.setRoleCode(role);
    return user;
  }

  private Map<String, Object> sheet(long gid, String name) {
    Map<String, Object> sheet = new LinkedHashMap<String, Object>();
    sheet.put("gid", gid);
    sheet.put("name", name);
    return sheet;
  }
}
