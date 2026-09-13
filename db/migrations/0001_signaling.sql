-- 0001_signaling.sql
-- Adds WebRTC signaling columns to the chat table.
-- Host writes `offer`, joiner writes `answer` (non-trickle ICE:
-- the full SDP including gathered candidates is stored in one write).
--
-- Apply once via the Supabase dashboard -> SQL editor.

ALTER TABLE chat
  ADD COLUMN IF NOT EXISTS offer  TEXT,
  ADD COLUMN IF NOT EXISTS answer TEXT;
