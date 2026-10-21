package com.example.nms.security;

/** One configured API key: its id, owner, and the hash of the full key. Never the key itself. */
record StoredApiKey(String keyId, String sourceSystem, HashedSecret secret) {}
