-- Run once on databases created before multi-provider image jobs.
-- The deployment script checks information_schema before applying these statements
-- because the production MySQL version does not support ADD COLUMN IF NOT EXISTS.
ALTER TABLE image_job ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'QWEN';
ALTER TABLE image_job ADD COLUMN model VARCHAR(128) NOT NULL DEFAULT 'qwen-image-3.0-pro';
