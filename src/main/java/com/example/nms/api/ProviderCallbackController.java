package com.example.nms.api;

import com.example.nms.delivery.DeliveryCallbacks;
import com.example.nms.security.WebhookProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Provider delivery callbacks (ADR-0003). The webhook-token filter has authenticated the provider
 * in the path; this endpoint only validates and hands the callback to {@link DeliveryCallbacks}.
 * Applied, ignored, and unknown callbacks are all {@code 200} with no body, so vendors do not
 * retry.
 */
@RestController
class ProviderCallbackController {

  private final CallbackRequestValidator validator;
  private final DeliveryCallbacks callbacks;

  ProviderCallbackController(CallbackRequestValidator validator, DeliveryCallbacks callbacks) {
    this.validator = validator;
    this.callbacks = callbacks;
  }

  @PostMapping("/api/v1/webhooks/{provider}")
  ResponseEntity<Void> callback(WebhookProvider provider, @RequestBody JsonNode body) {
    callbacks.apply(validator.validate(provider, body));
    return ResponseEntity.ok().build();
  }
}
