-- Extensions every later migration may use.
-- pg_trgm: trigram GIN indexes for LIKE '%term%' searches (engineering rule 4).

CREATE EXTENSION IF NOT EXISTS pg_trgm;
