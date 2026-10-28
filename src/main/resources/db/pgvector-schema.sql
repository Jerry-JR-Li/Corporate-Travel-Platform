CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS travel_knowledge_corpus (
    corpus text PRIMARY KEY,
    revision text NOT NULL,
    embedding_model text NOT NULL DEFAULT 'text-embedding-v4',
    chunk_count integer NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE travel_knowledge_corpus ADD COLUMN IF NOT EXISTS embedding_model text NOT NULL DEFAULT 'text-embedding-v4';
CREATE TABLE IF NOT EXISTS travel_knowledge_chunk (
    corpus text NOT NULL,
    chunk_id text NOT NULL,
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}',
    embedding vector(1024) NOT NULL,
    PRIMARY KEY (corpus, chunk_id)
);
CREATE INDEX IF NOT EXISTS travel_knowledge_embedding_hnsw
    ON travel_knowledge_chunk USING hnsw (embedding vector_cosine_ops);
