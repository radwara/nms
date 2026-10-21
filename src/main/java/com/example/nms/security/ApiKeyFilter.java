package com.example.nms.security;

import com.example.nms.api.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Authenticates {@code /api/**} requests by {@code X-API-Key} and exposes the {@link SourceSystem}.
 * Webhooks are excluded: {@link WebhookTokenFilter} handles them.
 */
class ApiKeyFilter extends CredentialFilter<SourceSystem> {

  static final String HEADER = "X-API-Key";

  private final ApiKeyAuthenticator authenticator;

  ApiKeyFilter(ApiKeyAuthenticator authenticator, ErrorResponseWriter errorWriter) {
    super(errorWriter, HEADER, "API key", "ApiKey", SourceSystem.REQUEST_ATTRIBUTE);
    this.authenticator = authenticator;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return SecurityPaths.isWebhook(request);
  }

  @Override
  Optional<SourceSystem> authenticate(HttpServletRequest request, String credential) {
    return authenticator.authenticate(credential);
  }
}
