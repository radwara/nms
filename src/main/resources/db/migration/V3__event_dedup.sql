-- Event-level deduplication (#14, ADR-0006). Additive; no backfill: rows written before V3 have no
-- content_hash, so they never match the dedup lookup.
-- Production (PostgreSQL): create the index concurrently, outside the Flyway transaction; a partial
-- index "where duplicate_of is null" would also skip suppressed rows (H2 has no partial indexes).

-- The original notification a suppressed one repeats; null for originals. No foreign key: references
-- by id like audit_event.notification_id, so an original can be purged first.
alter table notification add column duplicate_of uuid null;

-- SHA-256 (lowercase hex) of subject, body, and severity; written on every new row.
alter table notification add column content_hash char(64) null;

create index ix_notification_event_dedup
    on notification (source_system, event_id, type, recipient_id, content_hash, created_at);
