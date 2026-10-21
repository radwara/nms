package com.example.nms.security;

import com.example.nms.api.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Authenticates {@code /api/v1/webhooks/{provider}/**} by {@code X-Webhook-Token}. The token must
 * be configured for the provider named in the path; fail-closed when none are configured.
 */
class WebhookTokenFilter extends CredentialFilter<WebhookProvider> {

  static final String HEADER = "X-Webhook-Token";

  private final WebhookTokenAuthenticator authenticator;

  WebhookTokenFilter(WebhookTokenAuthenticator authenticator, ErrorResponseWriter errorWriter) {
    super(errorWriter, HEADER, "Webhook token", "Token", WebhookProvider.REQUEST_ATTRIBUTE);
    this.authenticator = authenticator;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !SecurityPaths.isWebhook(request);
  }

  @Override
  Optional<WebhookProvider> authenticate(HttpServletRequest request, String credential) {
    String rest = SecurityPaths.path(request).substring(SecurityPaths.WEBHOOKS_PREFIX.length());
    int slash = rest.indexOf('/');
    String provider = slash >= 0 ? rest.substring(0, slash) : rest;
    return authenticator.authenticate(provider, credential);
  }
}
