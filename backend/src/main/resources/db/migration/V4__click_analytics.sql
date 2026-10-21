-- Raw click events: append-only, one row per human or bot click, partitioned by month so old data can be
-- dropped instantly (DROP TABLE on a partition) instead of with a slow DELETE + vacuum.
-- No foreign key on purpose: it would add a lookup to every bulk insert on the ingestion path.
-- No raw IP address is stored: only a hash that changes every day (see VisitorHasher).
-- Partitions are created by the application (PartitionManager), not here.
CREATE TABLE click_events (
    link_id       BIGINT      NOT NULL,
    clicked_at    TIMESTAMPTZ NOT NULL,
    country       VARCHAR(2),
    device        VARCHAR(16) NOT NULL,
    browser       VARCHAR(64),
    os            VARCHAR(64),
    referrer_host VARCHAR(255),
    visitor_hash  VARCHAR(32) NOT NULL,
    bot           BOOLEAN     NOT NULL DEFAULT FALSE
) PARTITION BY RANGE (clicked_at);

-- Created on the parent, so every current and future partition gets it automatically.
CREATE INDEX idx_click_events_link_time ON click_events (link_id, clicked_at);

-- Pre-aggregated per link per UTC day. Dashboards read this instead of scanning raw events, and it outlives them.
-- Updated in the same transaction as the raw insert, so the two cannot drift apart.
CREATE TABLE link_daily_stats (
    link_id    BIGINT NOT NULL REFERENCES links (id) ON DELETE CASCADE,
    day        DATE   NOT NULL,
    clicks     BIGINT NOT NULL DEFAULT 0,   -- human clicks
    bot_clicks BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (link_id, day)
);
