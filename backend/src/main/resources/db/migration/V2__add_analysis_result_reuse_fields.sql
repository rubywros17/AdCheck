ALTER TABLE analyses
    ADD COLUMN normalized_url VARCHAR(2048),
    ADD COLUMN content_hash VARCHAR(64),
    ADD COLUMN pipeline_version VARCHAR(50),
    ADD COLUMN result_json JSONB,
    ADD COLUMN error_message TEXT,
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE analyses
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE analyses
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX idx_analyses_result_reuse_lookup
    ON analyses (
        normalized_url,
        content_hash,
        pipeline_version,
        status,
        completed_at DESC
    );

CREATE UNIQUE INDEX uk_analyses_active_result_reuse
    ON analyses (normalized_url, content_hash, pipeline_version)
    WHERE status IN ('PENDING', 'PROCESSING')
      AND normalized_url IS NOT NULL
      AND content_hash IS NOT NULL
      AND pipeline_version IS NOT NULL;
