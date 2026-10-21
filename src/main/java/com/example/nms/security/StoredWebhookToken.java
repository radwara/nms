package com.example.nms.security;

/** One configured webhook token: the provider it authenticates and the token's hash. */
record StoredWebhookToken(String provider, HashedSecret secret) {}
