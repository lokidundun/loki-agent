CREATE TABLE IF NOT EXISTS sessions (
    key TEXT PRIMARY KEY,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    last_consolidated INTEGER DEFAULT 0,
    metadata TEXT,
    last_user_at TEXT,
    next_seq INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    session_key TEXT NOT NULL,
    seq INTEGER NOT NULL,
    role TEXT NOT NULL,
    content TEXT,
    tool_chain TEXT,
    extra TEXT,
    ts TEXT NOT NULL,
    UNIQUE(session_key, seq)
);
