package com.example.nms.security;

/**
 * The provider authenticated by the request's webhook token. Declare it as a controller method
 * parameter to receive it.
 */
public record WebhookProvider(String name) {

  static final String REQUEST_ATTRIBUTE = WebhookProvider.class.getName();
}
