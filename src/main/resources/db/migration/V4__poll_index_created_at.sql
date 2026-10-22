-- #51: the poll orders by next_attempt_at, created_at (#46 FIFO tiebreak). With created_at in the
-- index the ORDER BY follows the index, so a poll stops after one batch instead of sorting every due
-- row of its status and priority. Production (PostgreSQL): build the new index CONCURRENTLY under a
-- new name in its own non-transactional migration, then drop the old one.
drop index ix_delivery_poll;

create index ix_delivery_poll on delivery (status, priority, next_attempt_at, created_at);
