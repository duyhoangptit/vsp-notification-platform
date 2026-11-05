-- Spring Modulith 2.1 JDBC Event Publication Registry, PostgreSQL schema v2 (copied from
-- spring-modulith-events-jdbc-2.1.2.jar: schemas/v2/schema-postgresql.sql). Completion mode DELETE, no archive.
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
CREATE INDEX IF NOT EXISTS event_publication_incomplete_idx ON event_publication (publication_date) WHERE completion_date IS NULL;
