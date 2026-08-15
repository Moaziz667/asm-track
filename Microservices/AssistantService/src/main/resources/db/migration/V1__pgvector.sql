-- pgvector: the extension backing semantic retrieval. Requires an image that ships it
-- (pgvector/pgvector:pg16 in docker-compose), NOT stock postgres:16-alpine.
CREATE EXTENSION IF NOT EXISTS vector;
