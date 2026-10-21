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
- Stored per key: `keyId`, `sourceSystem`, `SHA-256(secret)`, `expiresAt`, `revoked`. Supplied via config / environment for the prototype.
- Lookup by `keyId`, compare hashes in constant time (`MessageDigest.isEqual`).
- Multiple active keys per source allow rotation with overlap. Plaintext is shown once, at generation, and never stored.
- `sourceSystem` comes from the authenticated key, never from the request body.
- Reads are tenant-scoped: a source can only read its own notifications; others get 404.
- Failures return a generic 401. Logs carry `keyId` only, never the secret. Auth failures are counted in metrics.

SHA-256 rather than bcrypt/argon2: keys are 256-bit random values, not low-entropy passwords, so a slow hash adds per-request latency without adding security.

## Production path

Production systems in this domain use HMAC request signing (timestamp + nonce, D) or JWTs (C). Authentication sits behind one filter and an authenticator interface, so either can replace B without touching business code. Key storage moves from config to a secrets store or database with an issuance/rotation API.

## Consequences

- Plus: simple, no extra infrastructure, no plaintext secrets at rest, rotation without a client outage.
- Minus: bearer key is sent on every request (TLS required); no replay protection; rotation needs a config change and restart (no admin API).
