package com.example.nms.support;

import com.example.nms.api.ApiError;
import com.example.nms.api.ApiException;
import com.example.nms.api.ErrorCode;
import com.example.nms.security.SourceSystem;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Test-only endpoints behind the API platform (auth, error contract, tracing). */
@RestController
@RequestMapping("/api/v1/test")
public class ApiTestController {

  public static final String UNEXPECTED_DETAIL = "unexpected-detail-must-not-leak";
  public static final String STATUS_REASON = "status-reason-must-not-leak";
  public static final String LOG_PROBE = "trace-log-probe";

  private static final Logger log = LoggerFactory.getLogger(ApiTestController.class);

  record Probe(String name) {}

  record Item(int qty) {}

  record Typed(int count, List<Item> items) {}

  @GetMapping("/source")
  Map<String, String> source(SourceSystem sourceSystem) {
    return Map.of("sourceSystem", sourceSystem.name());
  }

  @PostMapping("/echo")
  Map<String, String> echo(@RequestBody Probe probe) {
    return Map.of("received", String.valueOf(probe.name() != null));
  }

  @PostMapping("/typed")
  Map<String, Integer> typed(@RequestBody Typed typed) {
    return Map.of("count", typed.count());
  }

  @GetMapping("/header")
  Map<String, Integer> header(@RequestHeader("X-Probe") String probe) {
    return Map.of("length", probe.length());
  }

  @GetMapping("/param")
  Map<String, Integer> param(@RequestParam("count") int count) {
    return Map.of("count", count);
  }

  @GetMapping("/items/{id}")
  Map<String, String> item(@PathVariable("id") UUID id) {
    return Map.of("version", String.valueOf(id.version()));
  }

  @GetMapping("/status/{code}")
  void status(@PathVariable("code") int code) {
    throw new ResponseStatusException(HttpStatus.valueOf(code), STATUS_REASON);
  }

  @GetMapping("/send-error/{code}")
  void sendError(@PathVariable("code") int code, HttpServletResponse response) throws IOException {
    response.sendError(code, STATUS_REASON);
  }

  @GetMapping("/log")
  void log() {
    log.info(LOG_PROBE);
  }

  @GetMapping("/unexpected")
  void unexpected() {
    throw new IllegalStateException(UNEXPECTED_DETAIL);
  }

  @GetMapping("/api-exception")
  void apiException() {
    throw new ApiException(
        ApiError.of(ErrorCode.FIELD_MISSING, "eventId"),
        ApiError.of(ErrorCode.INVALID_VALUE, "recipients[0].userId"));
  }
}
