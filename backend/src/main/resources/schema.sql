-- MySQL 8.0.16+ / 8.4: execute manually with a schema-management account.
-- Runtime users need SELECT, INSERT, UPDATE and DELETE only. This file does not upgrade existing tables.
CREATE TABLE IF NOT EXISTS books (
 id VARCHAR(36) PRIMARY KEY,
 title VARCHAR(120) NOT NULL,
 author VARCHAR(100) NOT NULL,
 description VARCHAR(4000) NOT NULL DEFAULT '',
 encoding VARCHAR(30) NOT NULL,
 chapter_count INT NOT NULL,
 volume_count INT NOT NULL,
 character_count BIGINT NOT NULL,
 preface LONGTEXT NOT NULL,
 sha256 VARCHAR(64) NOT NULL UNIQUE,
 catalog_published BOOLEAN NOT NULL DEFAULT FALSE,
 text_published BOOLEAN NOT NULL DEFAULT FALSE,
 created_at BIGINT NOT NULL,
 CONSTRAINT chk_books_visibility CHECK (NOT text_published OR catalog_published),
 INDEX idx_books_created (created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Exactly one row per chapter. Directory queries select metadata without loading content.
CREATE TABLE IF NOT EXISTS chapters (
 book_id VARCHAR(36) NOT NULL,
 chapter_index INT NOT NULL,
 title VARCHAR(160) NOT NULL,
 volume VARCHAR(160) NOT NULL,
 content LONGTEXT NOT NULL,
 character_count INT NOT NULL,
 PRIMARY KEY(book_id, chapter_index),
 CONSTRAINT fk_chapters_book FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,
 CONSTRAINT chk_chapters_position CHECK (chapter_index >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS reading_progress (
 book_id VARCHAR(36) PRIMARY KEY,
 chapter_index INT NOT NULL,
 paragraph_index INT NOT NULL,
 updated_at BIGINT NOT NULL,
 CONSTRAINT fk_progress_book FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,
 CONSTRAINT chk_progress_position CHECK (chapter_index >= 0 AND paragraph_index >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS reading_sessions (
 id VARCHAR(36) PRIMARY KEY,
 book_id VARCHAR(36) NOT NULL,
 chapter_index INT NOT NULL,
 paragraph_index INT NOT NULL,
 started_at BIGINT NOT NULL,
 elapsed_seconds INT NOT NULL,
 reading_day VARCHAR(10) NOT NULL,
 updated_at BIGINT NOT NULL,
 CONSTRAINT fk_sessions_book FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,
 CONSTRAINT chk_sessions_seconds CHECK (elapsed_seconds BETWEEN 0 AND 1800),
 INDEX idx_sessions_day (reading_day),
 INDEX idx_sessions_updated (updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Notes / reading impressions are persisted here, not in Redis.
CREATE TABLE IF NOT EXISTS bookmarks (
 id VARCHAR(36) PRIMARY KEY,
 book_id VARCHAR(36) NOT NULL,
 chapter_index INT NOT NULL,
 paragraph_index INT NOT NULL,
 note VARCHAR(4000) NOT NULL DEFAULT '',
 published BOOLEAN NOT NULL DEFAULT FALSE,
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 UNIQUE(book_id, chapter_index, paragraph_index),
 CONSTRAINT fk_bookmarks_book FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,
 CONSTRAINT chk_bookmarks_position CHECK (chapter_index >= 0 AND paragraph_index >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
