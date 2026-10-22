-- Event-level deduplication (#14, ADR-0006). Additive; no backfill: rows written before V3 have no
-- content_hash and routed = false, so they never match the dedup lookup.
-- Verified on H2 only. Production (PostgreSQL): replace the index below with a partial index
-- "where duplicate_of is null and routed", built in its own non-transactional migration with
-- CREATE INDEX CONCURRENTLY (H2 has no partial indexes).

-- The original notification a suppressed one repeats; null for originals. No foreign key: references
-- by id like audit_event.notification_id, so an original can be purged first.
alter table notification add column duplicate_of uuid null;

-- SHA-256 (lowercase hex) of subject, body, and severity; written on every new row.
alter table notification add column content_hash char(64) null;

-- Routing created at least one delivery for it at intake (same transaction). Only routed originals
-- anchor dedup: a NOT_ROUTED one sent nothing, so a resend must go through.
alter table notification add column routed boolean default false not null;

-- Equality columns first, then the window range; recipients are filtered in memory, so a storm of
-- suppressed repeats or unrouted rows is skipped by the index, not read.
create index ix_notification_event_dedup
    on notification (source_system, event_id, type, content_hash, duplicate_of, routed, created_at);
