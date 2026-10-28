package com.gogo.travel.knowledge;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/** Separate pooled PostgreSQL connection; MySQL remains the business datasource. */
public class PgVectorRepository implements AutoCloseable {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int dimensions;
    private final String embeddingModel;
    public PgVectorRepository(DataSource source, int dimensions) {
        this(source, dimensions, "text-embedding-v4");
    }
    public PgVectorRepository(DataSource source, int dimensions, String embeddingModel) {
        this.source = source; this.dimensions = dimensions;
        this.embeddingModel = embeddingModel;
        this.jdbc = new JdbcTemplate(source); this.jdbc.setQueryTimeout(30);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.transaction.setTimeout(60);
    }
    public static PgVectorRepository create(PgVectorProperties p) {
        DruidDataSource pool = new DruidDataSource();
        pool.setUrl(p.url()); pool.setUsername(p.username()); pool.setPassword(p.password());
        pool.setDriverClassName("org.postgresql.Driver"); pool.setMaxActive(6); pool.setMaxWait(5000);
        pool.setConnectTimeout(5000); pool.setSocketTimeout(30000);
        return new PgVectorRepository(pool, p.dimensions(), p.embeddingModel());
    }
    public void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("db/pgvector-schema.sql")).execute(source);
    }
    public boolean isCurrent(String corpus, String revision) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM travel_knowledge_corpus c WHERE corpus=? AND revision=? AND embedding_model=?
                AND chunk_count=(SELECT count(*) FROM travel_knowledge_chunk k WHERE k.corpus=c.corpus))
                """, Boolean.class, corpus, revision, embeddingModel));
    }
    public void replaceCorpus(String corpus, String revision, List<Document> documents) {
        // Validate before opening the transaction. Failed embedding/import never deletes the live corpus.
        documents.forEach(d -> vector(d.getEmbedding()));
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, corpus);
            if (isCurrent(corpus, revision)) return;
            jdbc.update("DELETE FROM travel_knowledge_chunk WHERE corpus=?", corpus);
            for (Document doc : documents) {
                jdbc.update("""
                    INSERT INTO travel_knowledge_chunk(corpus,chunk_id,content,metadata,embedding)
                    VALUES (?,?,?,?::jsonb,?::vector)
                    """, corpus, doc.getMetadata().getChunkId(), doc.getMetadata().getContentText(),
                        JSON.toJSONString(doc.getMetadata().getPayload()), vector(doc.getEmbedding()));
            }
            jdbc.update("""
                INSERT INTO travel_knowledge_corpus(corpus,revision,chunk_count,embedding_model) VALUES (?,?,?,?)
                ON CONFLICT(corpus) DO UPDATE SET revision=excluded.revision,
                  chunk_count=excluded.chunk_count,embedding_model=excluded.embedding_model,updated_at=now()
                """, corpus, revision, documents.size(), embeddingModel);
        });
    }
    public void addDocuments(String corpus, List<Document> documents) {
        documents.forEach(d -> vector(d.getEmbedding()));
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, corpus);
            checkModel(corpus);
            for (Document doc : documents) {
                jdbc.update("""
                    INSERT INTO travel_knowledge_chunk(corpus,chunk_id,content,metadata,embedding) VALUES (?,?,?,?::jsonb,?::vector)
                    ON CONFLICT(corpus,chunk_id) DO UPDATE SET content=excluded.content,metadata=excluded.metadata,embedding=excluded.embedding
                    """, corpus,doc.getMetadata().getChunkId(),doc.getMetadata().getContentText(),
                    JSON.toJSONString(doc.getMetadata().getPayload()),vector(doc.getEmbedding()));
            }
            jdbc.update("""
                INSERT INTO travel_knowledge_corpus(corpus,revision,chunk_count,embedding_model)
                VALUES (?,?,(SELECT count(*) FROM travel_knowledge_chunk WHERE corpus=?),?)
                ON CONFLICT(corpus) DO UPDATE SET revision=excluded.revision,chunk_count=excluded.chunk_count,
                  embedding_model=excluded.embedding_model,updated_at=now()
                """,corpus,"manual-"+UUID.randomUUID(),corpus,embeddingModel);
        });
    }
    private void checkModel(String corpus) {
        List<String> models=jdbc.queryForList("SELECT embedding_model FROM travel_knowledge_corpus WHERE corpus=?",String.class,corpus);
        if (!models.isEmpty() && !embeddingModel.equals(models.getFirst())) {
            throw new IllegalStateException("Knowledge embedding model changed; re-import corpus before querying");
        }
    }
    public List<Document> search(String corpus, double[] embedding, int limit, double threshold) {
        checkModel(corpus);
        String vector = vector(embedding);
        // Bound corpus and query parameters prevent crossing knowledge sources or injecting SQL.
        return jdbc.query("""
            SELECT chunk_id,content,metadata,1-(embedding <=> ?::vector) AS score
            FROM travel_knowledge_chunk WHERE corpus=?
            ORDER BY embedding <=> ?::vector LIMIT ?
            """, (rs, row) -> {
            Document doc = new Document(new DocumentMetadata(TextBlock.builder().text(rs.getString("content")).build(),
                    corpus, rs.getString("chunk_id"), JSON.parseObject(rs.getString("metadata"))));
            doc.setScore(rs.getDouble("score")); return doc;
        }, vector, corpus, vector, Math.min(Math.max(limit,1),100)).stream()
                .filter(doc -> doc.getScore() >= threshold).toList();
    }
    public String vector(double[] vector) {
        if (vector == null || vector.length != dimensions) throw new IllegalArgumentException("Embedding dimension mismatch");
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (double v : vector) {
            if (!Double.isFinite(v)) throw new IllegalArgumentException("Embedding contains non-finite values");
            out.add(Double.toString(v));
        }
        return out.toString();
    }
    @Override @PreDestroy public void close() {
        if (source instanceof DruidDataSource pool) pool.close();
    }
}
