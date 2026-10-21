package com.example.nms.security;

import com.example.nms.api.ErrorResponseWriter;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** API-key authentication for {@code /api/**} (ADR-0001). Actuator and other paths are outside. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiKeyProperties.class)
class SecurityConfiguration implements WebMvcConfigurer {

  @Bean
  ApiKeyAuthenticator apiKeyAuthenticator(ApiKeyProperties properties) {
    return new ApiKeyAuthenticator(ApiKeyConfigParser.parse(properties.apiKeys()));
  }

  @Bean
  FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
      ApiKeyAuthenticator authenticator, ErrorResponseWriter errorWriter) {
    FilterRegistrationBean<ApiKeyFilter> registration =
        new FilterRegistrationBean<>(new ApiKeyFilter(authenticator, errorWriter));
    registration.addUrlPatterns(ApiKeyFilter.URL_PATTERN);
    // Right after the trace id filter, before anything else touches the request.
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new SourceSystemArgumentResolver());
  }
}
