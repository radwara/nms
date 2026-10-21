package com.example.nms.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Raw credential configuration, parsed and validated by {@link CredentialsConfigParser}.
 *
 * @param apiKeys {@code NMS_API_KEYS}: {@code keyId:sourceSystem:sha256hex[,...]}
 * @param webhookTokens {@code NMS_WEBHOOK_TOKENS}: {@code provider:sha256hex[,...]}
 */
@ConfigurationProperties("nms.security")
record SecurityProperties(String apiKeys, String webhookTokens) {}
