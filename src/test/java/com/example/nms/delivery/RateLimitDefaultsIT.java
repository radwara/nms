package com.example.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.nms.common.Channel;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** #46: the shipped defaults in {@code application.yml}, per source system per minute. */
@SpringBootTest
class RateLimitDefaultsIT {

  @Autowired private RateLimitProperties properties;

  @Test
  void generousDefaultsPerSourcePerMinute() {
    assertThat(properties.window()).isEqualTo(Duration.ofMinutes(1));
    assertThat(properties.limit(Channel.EMAIL)).isEqualTo(600);
    assertThat(properties.limit(Channel.SMS)).isEqualTo(60);
    assertThat(properties.defaultPerSource()).isEqualTo(30);
  }
}
