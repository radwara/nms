package com.example.nms.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Raw API-key configuration from {@code NMS_API_KEYS}: {@code keyId:sourceSystem:sha256hex} entries
 * separated by commas. Parsed and validated by {@link ApiKeyConfigParser}.
 */
@ConfigurationProperties("nms.security")
record ApiKeyProperties(String apiKeys) {}
