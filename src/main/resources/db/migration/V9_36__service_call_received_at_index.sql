-- Retention reaper support (12.7.2026): the nightly purge deletes service_call rows
-- older than 60 days by received_at. Without an index every batch is a sequential
-- scan over the whole 6M-row audit table — cheap while the backlog sits at the heap
-- start, but degrading to a nightly full-table scan once freed pages get recycled.
-- ~130 MB one-time; blocks audit INSERTs for the build duration (~1-2 min at deploy,
-- acceptable — partner-call logging just queues briefly).
CREATE INDEX idx_service_call_received_at ON service_call (received_at);
