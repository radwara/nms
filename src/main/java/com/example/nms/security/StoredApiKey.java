package com.example.nms.security;

/** One configured API key: its id, owner, and the SHA-256 of the full key. Never the key itself. */
record StoredApiKey(String keyId, String sourceSystem, byte[] sha256) {}
