package com.smartuser.schedule.controller;

import com.smartuser.schedule.service.InspectorWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InspectorWorkspaceControllerMediaTest {
  @TempDir Path temporaryDirectory;

  @Test
  void streamsSingleByteRangeWithoutResourceRegionConverter() throws Exception {
    Path file = temporaryDirectory.resolve("video.mp4");
    Files.write(file, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
    InspectorWorkspaceService.PhotoDownload media = new InspectorWorkspaceService.PhotoDownload(
        new FileSystemResource(file), "video/mp4", "video-browser.mp4");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.RANGE, "bytes=2-5");

    ResponseEntity<StreamingResponseBody> response =
        new InspectorWorkspaceController(null).mediaResponse(media, false, request);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
    assertThat(response.getHeaders().getContentLength()).isEqualTo(4);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
    assertThat(output.toByteArray()).containsExactly(2, 3, 4, 5);
  }

  @Test
  void streamsCompleteMediaWithKnownLength() throws Exception {
    Path file = temporaryDirectory.resolve("photo.jpg");
    Files.write(file, new byte[] {10, 11, 12});
    InspectorWorkspaceService.PhotoDownload media = new InspectorWorkspaceService.PhotoDownload(
        new FileSystemResource(file), "image/jpeg", "photo.jpg");

    ResponseEntity<StreamingResponseBody> response = new InspectorWorkspaceController(null)
        .mediaResponse(media, false, new MockHttpServletRequest());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
    assertThat(output.toByteArray()).containsExactly(10, 11, 12);
  }

  @Test
  void playbackEndpointWritesRangeThroughSpringMvc() throws Exception {
    Path file = temporaryDirectory.resolve("playback.mp4");
    Files.write(file, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
    InspectorWorkspaceService.PhotoDownload media = new InspectorWorkspaceService.PhotoDownload(
        new FileSystemResource(file), "video/mp4", "playback-browser.mp4");
    InspectorWorkspaceService service = mock(InspectorWorkspaceService.class);
    when(service.videoPlayback(isNull(), eq(422L))).thenReturn(media);
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new InspectorWorkspaceController(service)).build();

    MvcResult pending = mvc.perform(get("/api/inspector/photos/422/playback")
            .header(HttpHeaders.RANGE, "bytes=3-6"))
        .andExpect(request().asyncStarted())
        .andReturn();

    mvc.perform(asyncDispatch(pending))
        .andExpect(status().isPartialContent())
        .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 3-6/10"))
        .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 4))
        .andExpect(content().bytes(new byte[] {3, 4, 5, 6}));
  }
}
