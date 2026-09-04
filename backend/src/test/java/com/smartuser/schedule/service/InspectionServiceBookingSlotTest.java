package com.smartuser.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.mapper.ImportBatchMapper;
import com.smartuser.schedule.mapper.InspectionMapper;
import com.smartuser.schedule.model.InspectionRecord;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InspectionServiceBookingSlotTest {

  @Test
  void excludesOpenSlotWhenInspectorDateAndTimeAreAlreadyOccupied() throws Exception {
    // Sheet 可能同时残留空位行和真实排班；同一人员、日期、时间只能保留真实排班。
    InspectionRecord occupiedTen = record(1L, "IB Passed", "Dylan", "2026-08-05", "10:00", "1 Jolie Vue Rd");
    InspectionRecord staleOpenTen = record(2L, "Open Slot", "Dylan", "2026-08-05", "10:00", "");
    InspectionRecord validOpenEleven = record(3L, "Open Slot", "Dylan", "2026-08-05", "11:00", "");

    List<InspectionRecord> slots = invokeRecordList("openSheetSlots",
        Arrays.asList(occupiedTen, staleOpenTen, validOpenEleven));

    assertThat(slots).extracting(InspectionRecord::getId).containsExactly(3L);
  }

  @Test
  void includesImportedBusinessStatusesInRouteAppointments() throws Exception {
    // IB Passed/NEW CRM 也是路线中的真实前后站，必须参与 Google 行车时间计算。
    InspectionRecord passed = record(1L, "IB Passed", "Dylan", "2026-08-05", "10:00", "1 Jolie Vue Rd");
    InspectionRecord newCrm = record(2L, "NEW CRM", "Dylan", "2026-08-05", "12:00", "13 Monterey Cres");
    InspectionRecord open = record(3L, "Open Slot", "Dylan", "2026-08-05", "11:00", "");

    List<InspectionRecord> appointments = invokeRecordList("routeAppointments", Arrays.asList(passed, newCrm, open));

    assertThat(appointments).extracting(InspectionRecord::getId).containsExactly(1L, 2L);
  }

  @Test
  void routeAppointmentsExposeSavedDurationInsteadOfDefaultingToSixtyMinutes() throws Exception {
    // 非标准/历史 product 标签也要使用 Book 保存的真实工时，否则中间空位会被错误判定为行车时间不足。
    InspectionRecord booked = record(1L, "Fixed", "Dylan", "2026-08-05", "10:00", "1 Jolie Vue Rd");
    booked.setProjects("Legacy product label");
    booked.setRawJson("{\"source\":\"Book\",\"durationMinutes\":30}");

    List<InspectionRecord> appointments = invokeRecordList("routeAppointments", List.of(booked));

    assertThat(appointments).singleElement()
        .extracting(InspectionRecord::getWorkDurationMinutes)
        .isEqualTo(30);
  }

  @Test
  void routeAppointmentsUseExplicitDurationWhenAlreadyCalculated() throws Exception {
    InspectionRecord imported = record(1L, "IB Passed", "Dylan", "2026-08-05", "10:00", "1 Jolie Vue Rd");
    imported.setProjects("Legacy product label");
    imported.setWorkDurationMinutes(45);

    List<InspectionRecord> appointments = invokeRecordList("routeAppointments", List.of(imported));

    assertThat(appointments).singleElement()
        .extracting(InspectionRecord::getWorkDurationMinutes)
        .isEqualTo(45);
  }

  @Test
  void acceptsManualAustralianAddressWhenSelectedStateCompletesIt() throws Exception {
    // 自动补全不可用时，用户只需输入门牌、街道和 suburb；State 下拉框补齐州信息。
    assertThat(invokeAddressValidation("353 Balwyn Road Balwyn North", "VIC")).isEqualTo("VIC");
    assertThat(invokeAddressValidation("Unit 2, 353 Balwyn Road, Balwyn North", "VIC")).isEqualTo("VIC");
    assertThat(invokeAddressValidation("15 Argyll Cct, Melton West VIC 3337, Australia", "VIC")).isEqualTo("VIC");
  }

  @Test
  void rejectsAnExplicitAddressStateThatConflictsWithSelectedState() {
    assertThatThrownBy(() -> invokeAddressValidation("353 Balwyn Road, Balwyn North, VIC 3104", "NSW"))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(BadRequestException.class)
        .hasRootCauseMessage("Selected state does not match the Australian address.");
  }

  @Test
  void stillRejectsAnAddressWithoutSuburbOrPostcode() {
    assertThatThrownBy(() -> invokeAddressValidation("353 Balwyn Road", "VIC"))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(BadRequestException.class)
        .hasRootCauseMessage("Enter a complete Australian address with a street number, street name/type, and suburb or postcode.");
  }

  @Test
  void newSheetWeekCanImportWhenOnlyApplicationBookingsAlreadyExist() {
    InspectionService service = new InspectionService(null, null, new ObjectMapper(), null,
        mock(PlatformTransactionManager.class));
    InspectionRecord booking = record(1L, "Booked", "Dylan", "2026-08-25", "10:00", "1 Test St");
    booking.setRawJson("{\"source\":\"Book\"}");

    assertThat(service.containsNonApplicationWeekRows(List.of(booking))).isFalse();

    InspectionRecord imported = record(2L, "Open Slot", "Dylan", "2026-08-25", "11:00", "");
    imported.setRawJson("{\"source\":\"Google Sheet\"}");
    assertThat(service.containsNonApplicationWeekRows(List.of(booking, imported))).isTrue();
  }

  @Test
  void recognizesBlankInspectorSheetRowAsOpenSpace() throws Exception {
    InspectionService service = service();
    Method method = InspectionService.class.getDeclaredMethod("isOpenSheetSlot", Map.class);
    method.setAccessible(true);
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("inspectiondate", "28-Aug");
    values.put("inspectiontime", "2:00 PM");
    values.put("inspector", "");

    assertThat((Boolean) method.invoke(service, values)).isTrue();
  }

  @Test
  void blankInspectorSlotCanBeAssignedDuringBooking() throws Exception {
    InspectionService service = service();
    InspectionRecord slot = record(1L, "Open Slot", "", "2026-08-28", "14:00", "");
    Method method = InspectionService.class.getDeclaredMethod("ensureOpenSheetSlot",
        InspectionRecord.class, LocalDate.class, String.class, String.class);
    method.setAccessible(true);

    method.invoke(service, slot, LocalDate.parse("2026-08-28"), "Ronit", "14:00");
  }

  @Test
  void reimportMatchesBookedBlankSlotBySourceRow() throws Exception {
    InspectionService service = service();
    InspectionRecord imported = record(1L, "Open Slot", "", "2026-08-28", "14:00", "");
    imported.setRawJson("{\"source\":\"Google Sheet\",\"sourceSheetGid\":690390920,\"sourceRow\":42}");
    InspectionRecord booked = record(2L, "Booked", "Ronit", "2026-08-28", "14:00", "1 Test St");
    booked.setRawJson("{\"source\":\"Book\",\"sourceSheetGid\":690390920,\"sourceRow\":42}");
    Method method = InspectionService.class.getDeclaredMethod("sameBookingSpace",
        InspectionRecord.class, InspectionRecord.class);
    method.setAccessible(true);

    assertThat((Boolean) method.invoke(service, imported, booked)).isTrue();
  }

  @Test
  void safeSheetMergeUpdatesOpenSpaceInPlace() {
    InspectionService service = service();
    InspectionRecord existing = record(41L, "Open Slot", "Dylan", "2026-08-26", "13:00", "");
    existing.setRawJson("{\"source\":\"Google Sheet\",\"sourceSheetGid\":392,\"sourceRow\":20}");
    InspectionRecord incoming = record(null, "Open Slot", "Rohien", "2026-08-26", "13:00", "");
    incoming.setRawJson("{\"source\":\"Google Sheet\",\"sourceSheetGid\":392,\"sourceRow\":20}");

    assertThat(service.mergeImportedSheetRecordIfSafe(existing, incoming)).isTrue();
    assertThat(existing.getId()).isEqualTo(41L);
    assertThat(existing.getInspector()).isEqualTo("Rohien");
  }

  @Test
  void safeSheetMergePreservesBookedOrLocallyUpdatedRecord() {
    InspectionService service = service();
    InspectionRecord existing = record(42L, "Unavailable", "Rohien", "2026-08-26", "13:00", "1 Test St");
    existing.setRawJson("{\"source\":\"Google Sheet\",\"sourceSheetGid\":392,\"sourceRow\":21}");
    InspectionRecord incoming = record(null, "Open Slot", "", "2026-08-26", "13:00", "");

    assertThat(service.mergeImportedSheetRecordIfSafe(existing, incoming)).isFalse();
    assertThat(existing.getStatus()).isEqualTo("Unavailable");
    assertThat(existing.getAddress()).isEqualTo("1 Test St");
  }

  @Test
  void safeSheetMergeRemovesOnlyOpenSpacesDeletedFromSameSheet() {
    InspectionService service = service();
    InspectionRecord stale = record(51L, "Open Slot", "Dylan", "2026-08-26", "13:00", "");
    stale.setRawJson("{\"source\":\"Google Sheet\",\"sourceSheetGid\":392,\"sourceRow\":20}");
    InspectionRecord retained = record(52L, "Open Slot", "Dylan", "2026-08-26", "14:00", "");
    retained.setRawJson("{\"source\":\"Google Sheet\",\"sourceSheetGid\":392,\"sourceRow\":21}");
    InspectionRecord otherSheet = record(53L, "Open Slot", "Dylan", "2026-08-26", "15:00", "");
    otherSheet.setRawJson("{\"source\":\"Google Sheet\",\"sourceSheetGid\":999,\"sourceRow\":20}");
    InspectionRecord booked = record(54L, "Booked", "Dylan", "2026-08-26", "16:00", "1 Test St");
    booked.setRawJson("{\"source\":\"Book\",\"sourceSheetGid\":392,\"sourceRow\":22}");
    InspectionRecord incoming = record(null, "Open Slot", "Dylan", "2026-08-26", "14:00", "");
    incoming.setRawJson("{\"source\":\"Google Sheet\",\"sourceSheetGid\":392,\"sourceRow\":21}");

    assertThat(service.staleOpenSheetSlotIds(
        List.of(stale, retained, otherSheet, booked), List.of(incoming), 392L))
        .containsExactly(51L);
  }

  @Test
  void alignsJanuaryDateToYearOfCrossYearWeek() {
    InspectionService service = service();

    LocalDate aligned = service.alignSheetDateToWeek(
        LocalDate.of(2026, 1, 2), LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3));

    assertThat(aligned).isEqualTo(LocalDate.of(2027, 1, 2));
  }

  @Test
  void recognizesRenamedImportedSheetByStableGid() {
    InspectionMapper inspectionMapper = mock(InspectionMapper.class);
    ImportBatchMapper importBatchMapper = mock(ImportBatchMapper.class);
    when(inspectionMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
    InspectionService service = new InspectionService(inspectionMapper, importBatchMapper,
        new ObjectMapper(), null, mock(PlatformTransactionManager.class));

    assertThat(service.hasImportedGoogleSheetWeek(392L, "Week 392 renamed")).isTrue();
  }

  @SuppressWarnings("unchecked")
  private List<InspectionRecord> invokeRecordList(String methodName, List<InspectionRecord> records) throws Exception {
    InspectionService service = service();
    Method method = InspectionService.class.getDeclaredMethod(methodName, List.class);
    method.setAccessible(true);
    return (List<InspectionRecord>) method.invoke(service, records);
  }

  private String invokeAddressValidation(String address, String state) throws Exception {
    InspectionService service = service();
    Method method = InspectionService.class.getDeclaredMethod("validateAustralianAddressAndState", String.class, String.class);
    method.setAccessible(true);
    return (String) method.invoke(service, address, state);
  }

  private InspectionService service() {
    return new InspectionService(null, null, new ObjectMapper(), null,
        mock(PlatformTransactionManager.class));
  }

  private InspectionRecord record(Long id, String status, String inspector, String date, String time, String address) {
    InspectionRecord record = new InspectionRecord();
    record.setId(id);
    record.setStatus(status);
    record.setInspector(inspector);
    record.setInspectionDate(LocalDate.parse(date));
    record.setInspectionTime(time);
    record.setAddress(address);
    return record;
  }
}
