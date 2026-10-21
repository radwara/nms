package com.example.nms.api;

import java.util.List;

/** The single error body returned by every endpoint and filter. */
public record ErrorBody(String traceId, List<ApiError> errors) {}
