package com.smartuser.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.mapper.ImportBatchMapper;
import com.smartuser.schedule.mapper.InspectionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InspectionMediaRetentionTest {
  private final InspectionMapper mapper = mock(InspectionMapper.class);
  private final InspectionService service = new InspectionService(mapper,
      mock(ImportBatchMapper.class), new ObjectMapper(), mock(GoogleSheetSyncService.class),
      mock(PlatformTransactionManager.class));

  @Test void replacementRejectsRecordsWithMedia() {
    when(mapper.countAttachedMedia(List.of(42L))).thenReturn(2L);
    assertThatThrownBy(() -> service.requireNoAttachedMedia(List.of(42L)))
        .isInstanceOf(BadRequestException.class).hasMessageContaining("uploaded photos or videos");
  }

  @Test void refreshCannotOverwriteOpenSlotWithEvidence() {
    com.smartuser.schedule.model.InspectionRecord existing = new com.smartuser.schedule.model.InspectionRecord();
    existing.setId(42L);
    existing.setStatus("Open Slot");
    existing.setMacId("original-customer");
    com.smartuser.schedule.model.InspectionRecord incoming = new com.smartuser.schedule.model.InspectionRecord();
    incoming.setMacId("another-customer");
    when(mapper.countAttachedMedia(List.of(42L))).thenReturn(1L);
    assertThat(service.mergeImportedSheetRecordIfSafe(existing, incoming)).isFalse();
    assertThat(existing.getMacId()).isEqualTo("original-customer");
  }

  @Test void singleDeleteDoesNotOrphanMedia() {
    when(mapper.countAttachedMedia(List.of(42L))).thenReturn(1L);
    assertThatThrownBy(() -> service.deleteRecord(42L)).isInstanceOf(BadRequestException.class);
    verify(mapper, never()).deleteById(anyLong());
  }

  @Test void bulkDeleteRejectsWholeSelectionWithMedia() {
    when(mapper.countAttachedMedia(List.of(42L, 43L))).thenReturn(1L);
    assertThatThrownBy(() -> service.deleteRecords(List.of(42L, 43L)))
        .isInstanceOf(BadRequestException.class);
    verify(mapper, never()).deleteBatchIds(anyList());
  }

  @Test void emptySelectionDoesNotGenerateInvalidSql() {
    service.requireNoAttachedMedia(List.of());
    verifyNoInteractions(mapper);
  }

  @Test void recordsWithoutMediaCanStillBeDeleted() {
    service.deleteRecord(43L);
    verify(mapper).deleteById(43L);
  }
}
