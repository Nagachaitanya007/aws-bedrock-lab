package com.aws.lab;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class DatabaseInitializer {

    public static void main(String[] args) {
        String dbUrl = DbConfig.getUrl();
        String dbUser = DbConfig.getUser();
        String dbPass = DbConfig.getPassword();

        System.out.println("[DatabaseInitializer] Connecting to database at: " + dbUrl);

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             Statement stmt = conn.createStatement()) {

            // 1. Extensions
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector;");
            stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm;");

            // 2. Base Chunks Table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS document_chunks (
                    id SERIAL PRIMARY KEY,
                    document_title TEXT NOT NULL,
                    section_title TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    metadata JSONB,
                    embedding vector(1024),
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                );
            """);

            // 3. Parent Document Registry (P0)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    document_id VARCHAR(128) NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    content_hash VARCHAR(64) NOT NULL,
                    document_title TEXT NOT NULL,
                    document_type VARCHAR(64) NOT NULL DEFAULT 'GENERAL',
                    business_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
                    lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
                    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
                    owner_id VARCHAR(64) NOT NULL DEFAULT 'system',
                    classification VARCHAR(32) NOT NULL DEFAULT 'INTERNAL',
                    allowed_roles TEXT[] NOT NULL DEFAULT ARRAY['ENGINEERING'],
                    s3_raw_key TEXT NOT NULL,
                    s3_ir_key TEXT,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (document_id, version)
                );
            """);

            // 4. Indexes for fast retrieval filtering and lifecycle state checks
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_docs_active_lookup ON documents (document_id, lifecycle_status);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_docs_tenant_auth ON documents (tenant_id, classification, lifecycle_status);");

            // 5. Promote document_id and document_version onto document_chunks
            stmt.execute("ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS document_id VARCHAR(128);");
            stmt.execute("ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS document_version INTEGER DEFAULT 1;");

            // Backfill existing chunks (if any exist from earlier test runs)
            stmt.execute("""
                UPDATE document_chunks 
                   SET document_id = COALESCE(metadata->>'document_id', 'doc-319b483c'),
                       document_version = COALESCE((metadata->>'document_version')::int, 1)
                 WHERE document_id IS NULL;
            """);

            stmt.execute("ALTER TABLE document_chunks ALTER COLUMN document_id SET NOT NULL;");
            stmt.execute("ALTER TABLE document_chunks ALTER COLUMN document_version SET NOT NULL;");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunks_doc_version ON document_chunks (document_id, document_version);");

            // 6. Retrieval Indexes
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding_hnsw 
                ON document_chunks 
                USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64);
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_document_chunks_content_gin 
                ON document_chunks 
                USING gin (to_tsvector('english', content));
            """);

            System.out.println("[DatabaseInitializer] P0 schema migration completed successfully.");

        } catch (Exception e) {
            System.err.println("[DatabaseInitializer] Migration failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}