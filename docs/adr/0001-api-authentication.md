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
- Stored per key: `keyId`, `sourceSystem`, SHA-256 of the full key string. Supplied via config / environment for the prototype.
- Lookup by `keyId`, compare hashes in constant time (`MessageDigest.isEqual`).
- Multiple active keys per source allow rotation with overlap: add the new hash, migrate callers, remove the old hash. Keys are generated with `openssl rand` and hashed once; the plaintext is never stored. No expiry, revocation, or issuance tooling in the prototype.
- `sourceSystem` comes from the authenticated key, never from the request body.
- Reads are tenant-scoped: a source can only read its own notifications; others get 404.
- Implemented as a plain request filter that writes the standard error body (not Spring Security), to keep 401s in the same format with minimal code.
- Missing key: 401 / 4011. Malformed key, unknown `keyId`, or wrong secret: 401 / 4012 with an identical body. Failures are logged at DEBUG with `keyId` only (for configured keyIds), never the key or its hash.
- Provider webhooks use the same scheme with a per-provider token in `X-Webhook-Token` (hash of the full token, constant-time compare, several tokens per provider). The token must belong to the provider in the path (`/api/v1/webhooks/{provider}/...`); a token for another provider is 4012. 401s carry `WWW-Authenticate: Token header="X-Webhook-Token"`.

SHA-256 rather than bcrypt/argon2: keys are 256-bit random values, not low-entropy passwords, so a slow hash adds per-request latency without adding security.

## Configuration

`NMS_API_KEYS` (property `nms.security.api-keys`): `keyId:sourceSystem:sha256hex[,...]`, where `sha256hex` is the lowercase hex SHA-256 of the full key. Entries may share a `sourceSystem` (rotation). Empty: every `/api` call gets 401. A malformed entry fails startup.

`NMS_WEBHOOK_TOKENS` (property `nms.security.webhook-tokens`): `provider:sha256hex[,...]`, provider `[a-z0-9-]{1,32}`. Same rules: several entries per provider allowed, empty rejects every callback, malformed fails startup.

Scope: API keys on `/api/**` except `/api/v1/webhooks/**`; webhook tokens on `/api/v1/webhooks/**`. `/actuator/**` is outside both. Both filters reject `.` / `..` path segments (400 / 4000).

Generate a key and its config entry:

```sh
KEY_ID=billing1   # [A-Za-z0-9], no underscore
KEY="nms_${KEY_ID}_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
echo "$KEY"       # hand to the caller once; never stored
printf '%s' "$KEY" | openssl dgst -sha256 -r | cut -d' ' -f1   # entry: billing1:<sourceSystem>:<this hash>
```

A webhook token is generated the same way without the `nms_<keyId>_` prefix; its entry is `<provider>:<hash>`.

### Demo credentials (dev profile and tests only)

Not secrets and deliberately not random. Hashes live in `application-dev.yml` and `src/test/resources/config/application.yml`; never configure them elsewhere. Startup logs a WARN when any of them is configured. The simulated vendors' callbacks (#11) send the plaintext webhook tokens below, so those are also in the same two files as `nms.simulator.callbacks.tokens`; real tokens never go there.

| API key | keyId | sourceSystem |
|---|---|---|
| `nms_demo1_dev-only` | `demo1` | `demo-app` |
| `nms_demo2_dev-only` | `demo2` | `demo-app` (second key, rotation) |
| `nms_other1_dev-only` | `other1` | `other-app` |

| Webhook token | provider |
|---|---|
| `sim-email-dev-only` | `sim-email` |
| `sim-sms-dev-only` | `sim-sms` |

## Production path

Production systems in this domain use HMAC request signing (timestamp + nonce, D) or JWTs (C). Authentication sits behind one filter and one authenticator class, so either replacement touches only those, not business code. Key storage moves from config to a secrets store or database with an issuance/rotation API.

## Revision (2026-10-20, design review)

Simplified after an independent design review commissioned by the engineer with over-complication as its focus: dropped `expiresAt`, `revoked`, and key-generation tooling; plain filter instead of Spring Security. Hashing, constant-time compare, and multi-key rotation kept.

## Consequences

- Plus: simple, no extra infrastructure, no plaintext secrets at rest, rotation without a client outage.
- Minus: bearer key is sent on every request (TLS required); no replay protection; no expiry or revocation; rotation needs a config change and restart (no admin API).
