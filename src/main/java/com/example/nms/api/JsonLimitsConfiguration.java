package com.example.nms.api;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;

/**
 * Bounds every JSON document the application reads. Jackson stops at the limit even for chunked
 * bodies with no {@code Content-Length}; the request fails with 413 / 4130. The largest valid
 * submission (100 recipients, maximum content) is well under the limit.
 */
@Configuration(proxyBeanMethods = false)
class JsonLimitsConfiguration {

  static final long MAX_DOCUMENT_BYTES = 64 * 1024;

  @Bean
  JsonFactoryBuilderCustomizer jsonDocumentLimit() {
    return builder ->
        builder.streamReadConstraints(
            StreamReadConstraints.builder().maxDocumentLength(MAX_DOCUMENT_BYTES).build());
  }
}
