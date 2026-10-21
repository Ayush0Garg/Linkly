-- Every click gets an id made when the click happens, so the same click delivered twice (a retry, or a message recovered
-- from a crashed instance) is stored once and counted once.
-- A unique index on a partitioned table has to include the partition key, hence (event_id, clicked_at): a given click
-- always has the same clicked_at, so this still rejects duplicates.
-- Older rows have no id (NULLs never conflict), which is fine.
ALTER TABLE click_events ADD COLUMN event_id UUID;
CREATE UNIQUE INDEX uq_click_events_event ON click_events (event_id, clicked_at);
