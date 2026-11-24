-- JobWatch schema. Idempotent: safe to run on every startup.
-- SQLite, single writer. WAL is a persistent per-database setting, enabled here on init.
PRAGMA journal_mode=WAL;

-- Normalized job (mirrors Job.java) plus notification/audit state.
CREATE TABLE IF NOT EXISTS jobs (
  id                    INTEGER PRIMARY KEY AUTOINCREMENT,
  source                TEXT    NOT NULL,
  source_job_id         TEXT    NOT NULL,
  title                 TEXT    NOT NULL,
  company               TEXT,
  location              TEXT,
  remote                INTEGER NOT NULL DEFAULT 0,
  workplace_type        TEXT,
  apply_url             TEXT,
  job_url               TEXT,
  description           TEXT,
  posted_at             TEXT,               -- ISO-8601, nullable
  min_experience_years  INTEGER,
  max_experience_years  INTEGER,
  salary_min            REAL,
  salary_max            REAL,
  salary_currency       TEXT,
  salary_estimated      INTEGER NOT NULL DEFAULT 0,
  employment_type       TEXT,
  fetched_at            TEXT,               -- ISO-8601
  stack                 TEXT,               -- comma-separated tech tags
  profile               TEXT,               -- winning profile name (null for suppressed non-matches)
  score                 INTEGER,            -- match score (null for suppressed non-matches)
  notified_at           TEXT,               -- ISO-8601, null until digest sent
  applied_at            TEXT,               -- ISO-8601, null until user marks applied (dashboard)
  created_at            TEXT    NOT NULL,   -- ISO-8601
  updated_at            TEXT,               -- ISO-8601, set on update
  UNIQUE (source, source_job_id)
);
CREATE INDEX IF NOT EXISTS idx_jobs_pending ON jobs(notified_at);

-- Per-source run statistics (maps from SourceStats).
CREATE TABLE IF NOT EXISTS source_runs (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  source        TEXT    NOT NULL,
  started_at    TEXT    NOT NULL,
  completed_at  TEXT,
  success       INTEGER NOT NULL,
  jobs_fetched  INTEGER,
  error         TEXT
);
CREATE INDEX IF NOT EXISTS idx_source_runs_source ON source_runs(source);

-- Dynamically discovered company roster (Phase 6 / FR-1a/FR-1b).
-- Replaces hardcoded slugs in application.yml. One row per (ats source, slug).
CREATE TABLE IF NOT EXISTS companies (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  source           TEXT    NOT NULL,            -- greenhouse | lever | ashby
  slug             TEXT    NOT NULL,
  display_name     TEXT,
  discovered_at    TEXT    NOT NULL,            -- ISO-8601
  last_ok_at       TEXT,                        -- last time the board returned jobs
  consecutive_404  INTEGER NOT NULL DEFAULT 0,
  enabled          INTEGER NOT NULL DEFAULT 1,
  UNIQUE (source, slug)
);
CREATE INDEX IF NOT EXISTS idx_companies_enabled ON companies(source, enabled);

-- Registered users. Passwords are BCrypt hashes; email is the login and the digest address.
CREATE TABLE IF NOT EXISTS users (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  email          TEXT    NOT NULL UNIQUE COLLATE NOCASE,
  name           TEXT,
  password_hash  TEXT    NOT NULL,
  digest_enabled INTEGER NOT NULL DEFAULT 1,  -- receive the daily 6 AM email
  created_at     TEXT    NOT NULL
);

-- Login sessions. Only a SHA-256 hash of the bearer token is stored.
CREATE TABLE IF NOT EXISTS sessions (
  token_hash  TEXT    PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  TEXT    NOT NULL,
  expires_at  TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

-- User-editable role profiles, owned by a user. Lists stored comma-separated.
-- Pre-accounts databases are rebuilt into this shape by SchemaMigrator.
CREATE TABLE IF NOT EXISTS profiles (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id        INTEGER REFERENCES users(id) ON DELETE CASCADE,  -- null only for legacy rows
  name           TEXT    NOT NULL,
  include_title  TEXT,                        -- role keywords (csv)
  stack          TEXT,                        -- skill/stack keywords (csv)
  exclude_title  TEXT,                        -- disqualifying keywords (csv)
  companies      TEXT,                        -- preferred companies (csv, empty means any)
  exp_min        INTEGER,                     -- desired min years (nullable)
  exp_max        INTEGER,                     -- desired max years (nullable)
  enabled        INTEGER NOT NULL DEFAULT 1,
  is_active      INTEGER NOT NULL DEFAULT 0,  -- active profiles gate what is shown and emailed
  created_at     TEXT    NOT NULL,
  updated_at     TEXT,
  UNIQUE (user_id, name)
);

-- Per-user matches: which stored job matched which of the user's profiles, plus that user's
-- notification/applied state. The jobs table itself is shared by all users.
CREATE TABLE IF NOT EXISTS user_jobs (
  user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  job_id       INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  profile      TEXT    NOT NULL,              -- name of the user's best-matching profile
  score        INTEGER,
  notified_at  TEXT,                          -- null until included in this user's digest
  applied_at   TEXT,                          -- null until the user marks it applied
  created_at   TEXT    NOT NULL,
  PRIMARY KEY (user_id, job_id)
);
CREATE INDEX IF NOT EXISTS idx_user_jobs_pending ON user_jobs(user_id, notified_at);

-- Company-discovery memory: every slug probed (live or not) and when, so incremental discovery
-- only tries new candidates and re-checks dead ones occasionally.
CREATE TABLE IF NOT EXISTS discovery_probes (
  slug       TEXT PRIMARY KEY,
  probed_at  TEXT NOT NULL
);
