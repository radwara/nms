package com.example.nms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** #6: logs are structured JSON (ECS) outside the dev profile. */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIT {

  @Test
  void logLinesAreJson(CapturedOutput output) {
    LoggerFactory.getLogger(StructuredLoggingIT.class).info("structured-logging-probe");

    String line =
        output
            .getOut()
            .lines()
            .filter(l -> l.contains("structured-logging-probe"))
            .findFirst()
            .orElseThrow();
    assertThat(line).startsWith("{").endsWith("}");
    assertThat(line)
        .contains(
            "\"@timestamp\"",
            "\"log\":{\"level\":\"INFO\"",
            "\"message\":\"structured-logging-probe\"");
  }
}
