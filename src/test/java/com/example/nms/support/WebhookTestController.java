package com.example.nms.support;

import com.example.nms.security.WebhookProvider;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint behind the webhook-token filter; does not clash with the real callbacks. */
@RestController
public class WebhookTestController {

  @PostMapping("/api/v1/webhooks/{provider}/test-probe")
  Map<String, String> probe(@PathVariable("provider") String provider, WebhookProvider webhook) {
    return Map.of("provider", webhook.name(), "pathProvider", provider);
  }
}
