package com.example.nms.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * #47: with the default {@code nms.kafka.enabled=false} no Kafka dispatch bean exists, no listener
 * container runs, and the poller has no grace window, so nothing connects to a broker.
 */
@SpringBootTest
class KafkaDisabledIT {

  @Autowired private ApplicationContext context;
  @Autowired private KafkaListenerEndpointRegistry listeners;

  @Test
  void noKafkaDispatchBeansAndNoListenerContainers() {
    assertThat(context.getBeansOfType(EmailDispatchPublisher.class)).isEmpty();
    assertThat(context.getBeansOfType(EmailDispatchListener.class)).isEmpty();
    assertThat(context.getBeansOfType(FirstDispatchGrace.class)).isEmpty();
    assertThat(listeners.getListenerContainers()).isEmpty();
  }
}
