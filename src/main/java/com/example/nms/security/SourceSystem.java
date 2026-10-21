package com.example.nms.security;

/**
 * The source system authenticated by the request's API key. Declare it as a controller method
 * parameter to receive it; a {@code sourceSystem} in a request body is never trusted.
 */
public record SourceSystem(String name) {

  static final String REQUEST_ATTRIBUTE = SourceSystem.class.getName();
}
