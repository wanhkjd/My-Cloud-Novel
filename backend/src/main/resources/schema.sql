CREATE TABLE IF NOT EXISTS books (
 id VARCHAR(36) PRIMARY KEY, title VARCHAR(120) NOT NULL, author VARCHAR(100) NOT NULL,
 description VARCHAR(4000) NOT NULL DEFAULT '', encoding VARCHAR(30) NOT NULL,
 chapter_count INT NOT NULL, volume_count INT NOT NULL, character_count BIGINT NOT NULL,
 preface LONGTEXT NOT NULL, sha256 VARCHAR(64) NOT NULL UNIQUE,
 catalog_published BOOLEAN NOT NULL DEFAULT FALSE, text_published BOOLEAN NOT NULL DEFAULT FALSE,
 created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS chapters (
 book_id VARCHAR(36) NOT NULL, chapter_index INT NOT NULL, title VARCHAR(160) NOT NULL,
 volume VARCHAR(160) NOT NULL, content LONGTEXT NOT NULL, character_count INT NOT NULL,
 PRIMARY KEY(book_id, chapter_index), FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS reading_progress (
 book_id VARCHAR(36) PRIMARY KEY, chapter_index INT NOT NULL, paragraph_index INT NOT NULL,
 updated_at BIGINT NOT NULL, FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS reading_sessions (
 id VARCHAR(36) PRIMARY KEY, book_id VARCHAR(36) NOT NULL, chapter_index INT NOT NULL, paragraph_index INT NOT NULL,
 started_at BIGINT NOT NULL, elapsed_seconds INT NOT NULL, reading_day VARCHAR(10) NOT NULL, updated_at BIGINT NOT NULL,
 FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS bookmarks (
 id VARCHAR(36) PRIMARY KEY, book_id VARCHAR(36) NOT NULL, chapter_index INT NOT NULL,
 paragraph_index INT NOT NULL, note VARCHAR(4000) NOT NULL DEFAULT '', published BOOLEAN NOT NULL DEFAULT FALSE,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 UNIQUE(book_id,chapter_index,paragraph_index), FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
);
