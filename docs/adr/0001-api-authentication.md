# ADR-0001: API authentication for source systems

- Status: Accepted (2026-10-20)
- Issue: #1
- Related: A-17

## Context

Callers are other systems (machine-to-machine). The spec does not define authentication. The prototype must run with no external infrastructure.

## Options

| Option | Summary | Trade-off |
|---|---|---|
| A. Static key in config | Plaintext key per source | Trivial; plaintext at rest; rotation needs downtime |
| B. Hashed, rotatable API keys | Only a hash is stored; several active keys per source | Small cost; leaked config does not leak usable keys |
| C. JWT (OAuth2 client credentials) | Short-lived tokens from an identity provider | Production standard; needs an identity provider |
| D. HMAC request signing | Per-request signature over method, path, body hash, timestamp, nonce | Secret never on the wire; replay protection; more client work |

## Decision

**B for the prototype. C or D in production.**

- Key format `nms_<keyId>_<secret>`; secret is 32 random bytes, base64url.
- Stored per key: `keyId`, `sourceSystem`, `SHA-256(secret)`. Supplied via config / environment for the prototype.
- Lookup by `keyId`, compare hashes in constant time (`MessageDigest.isEqual`).
- Multiple active keys per source allow rotation with overlap: add the new hash, migrate callers, remove the old hash. Keys are generated with `openssl rand` and hashed once; the plaintext is never stored. No expiry, revocation, or issuance tooling in the prototype.
- `sourceSystem` comes from the authenticated key, never from the request body.
- Reads are tenant-scoped: a source can only read its own notifications; others get 404.
- Implemented as a plain request filter that writes the standard error body (not Spring Security), to keep 401s in the same format with minimal code.
- Failures return a generic 401. Logs carry `keyId` only, never the secret. Auth failures are counted in metrics.

SHA-256 rather than bcrypt/argon2: keys are 256-bit random values, not low-entropy passwords, so a slow hash adds per-request latency without adding security.

## Production path

Production systems in this domain use HMAC request signing (timestamp + nonce, D) or JWTs (C). Authentication sits behind one filter and an authenticator interface, so either can replace B without touching business code. Key storage moves from config to a secrets store or database with an issuance/rotation API.

## Revision (2026-10-20, design review)

Simplified after an independent design review commissioned by the engineer with over-complication as its focus: dropped `expiresAt`, `revoked`, and key-generation tooling; plain filter instead of Spring Security. Hashing, constant-time compare, and multi-key rotation kept.

## Consequences

- Plus: simple, no extra infrastructure, no plaintext secrets at rest, rotation without a client outage.
- Minus: bearer key is sent on every request (TLS required); no replay protection; no expiry or revocation; rotation needs a config change and restart (no admin API).
