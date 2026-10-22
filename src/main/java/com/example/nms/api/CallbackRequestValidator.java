package com.example.nms.api;

import static com.example.nms.api.JsonFields.optionalCode;
import static com.example.nms.api.JsonFields.requiredEnum;
import static com.example.nms.api.JsonFields.requiredInstant;
import static com.example.nms.api.JsonFields.requiredText;
import static com.example.nms.api.JsonFields.requiredUuid;

import com.example.nms.common.CallbackEvent;
import com.example.nms.delivery.ProviderCallback;
import com.example.nms.security.WebhookProvider;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Validates the generic callback body (architecture.md, Provider callbacks): {@code deliveryId},
 * {@code providerMessageId}, {@code event}, optional {@code reasonCode}, {@code occurredAt}. Bound
 * as a tree, so every field error is reported at once (all 400-class). The provider is the one the
 * webhook token authenticated; unknown properties are ignored.
 */
@Component
class CallbackRequestValidator {

  /** Column {@code delivery.provider_message_id}. */
  static final int MAX_PROVIDER_MESSAGE_ID = 128;

  static final int MAX_REASON_CODE = 64;

  ProviderCallback validate(WebhookProvider provider, JsonNode body) {
    ApiErrors errors = new ApiErrors();
    if (body == null || !body.isObject()) {
      errors.add(ErrorCode.MALFORMED_JSON, null, "Request body must be a JSON object");
      errors.throwIfAny();
    }
    UUID deliveryId = requiredUuid(body, "deliveryId", errors);
    String providerMessageId =
        requiredText(
            body, "providerMessageId", "providerMessageId", MAX_PROVIDER_MESSAGE_ID, errors);
    CallbackEvent event = requiredEnum(body, "event", CallbackEvent.class, errors);
    String reasonCode = optionalCode(body, "reasonCode", MAX_REASON_CODE, errors);
    Instant occurredAt = requiredInstant(body, "occurredAt", errors);
    errors.throwIfAny();
    return new ProviderCallback(
        deliveryId, provider.name(), event, providerMessageId, reasonCode, occurredAt);
  }
}
