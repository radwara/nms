package com.example.nms.security;

import com.example.nms.api.ErrorResponseWriter;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Credential filters (ADR-0001): API keys on {@code /api/**}, webhook tokens on {@code
 * /api/v1/webhooks/**}. Actuator and other paths are outside both.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfiguration implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

  // Documented demo credentials (ADR-0001), for dev and tests only.
  private static final Set<String> DEMO_KEY_IDS = Set.of("demo1", "demo2", "other1");
  private static final List<String> DEMO_WEBHOOK_TOKENS =
      List.of("sim-email-dev-only", "sim-sms-dev-only");

  // Right after the trace id filter, before anything else touches the request.
  private static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  @Bean
  ApiKeyAuthenticator apiKeyAuthenticator(SecurityProperties properties) {
    List<StoredApiKey> keys = CredentialsConfigParser.apiKeys(properties.apiKeys());
    List<String> demo =
        keys.stream().map(StoredApiKey::keyId).filter(DEMO_KEY_IDS::contains).toList();
    if (!demo.isEmpty()) {
      log.warn("Demo API keys configured (keyIds {}); for dev and tests only", demo);
    }
    return new ApiKeyAuthenticator(keys);
  }

  @Bean
  WebhookTokenAuthenticator webhookTokenAuthenticator(SecurityProperties properties) {
    List<StoredWebhookToken> tokens =
        CredentialsConfigParser.webhookTokens(properties.webhookTokens());
    List<String> demo =
        tokens.stream()
            .filter(t -> DEMO_WEBHOOK_TOKENS.stream().anyMatch(t.secret()::matches))
            .map(StoredWebhookToken::provider)
            .toList();
    if (!demo.isEmpty()) {
      log.warn("Demo webhook tokens configured (providers {}); for dev and tests only", demo);
    }
    return new WebhookTokenAuthenticator(tokens);
  }

  @Bean
  FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
      ApiKeyAuthenticator authenticator, ErrorResponseWriter errorWriter) {
    FilterRegistrationBean<ApiKeyFilter> registration =
        new FilterRegistrationBean<>(new ApiKeyFilter(authenticator, errorWriter));
    registration.addUrlPatterns(SecurityPaths.API_PATTERN);
    registration.setOrder(FILTER_ORDER);
    return registration;
  }

  @Bean
  FilterRegistrationBean<WebhookTokenFilter> webhookTokenFilter(
      WebhookTokenAuthenticator authenticator, ErrorResponseWriter errorWriter) {
    FilterRegistrationBean<WebhookTokenFilter> registration =
        new FilterRegistrationBean<>(new WebhookTokenFilter(authenticator, errorWriter));
    registration.addUrlPatterns(SecurityPaths.WEBHOOKS_PATTERN);
    registration.setOrder(FILTER_ORDER);
    return registration;
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new PrincipalArgumentResolver());
  }
}
