package com.example.nms.support;

import com.example.nms.api.ApiError;
import com.example.nms.api.ApiException;
import com.example.nms.api.ErrorCode;
import com.example.nms.security.SourceSystem;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints behind the API platform (auth, error contract, tracing). */
@RestController
@RequestMapping("/api/v1/test")
public class ApiTestController {

  public static final String UNEXPECTED_DETAIL = "unexpected-detail-must-not-leak";

  record Probe(String name) {}

  @GetMapping("/source")
  Map<String, String> source(SourceSystem sourceSystem) {
    return Map.of("sourceSystem", sourceSystem.name());
  }

  @PostMapping("/echo")
  Map<String, String> echo(@RequestBody Probe probe) {
    return Map.of("received", String.valueOf(probe.name() != null));
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
