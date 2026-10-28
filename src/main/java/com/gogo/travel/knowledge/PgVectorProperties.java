package com.gogo.travel.knowledge;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("app.knowledge.pgvector")
public record PgVectorProperties(String url, String username, String password, int dimensions,
                                 String embeddingModel, boolean initializeSchema, boolean importOnStartup) {
    public PgVectorProperties {
        if (dimensions == 0) dimensions = 1024;
        if (dimensions != 1024) throw new IllegalArgumentException("Knowledge schema requires 1024-dimensional embeddings");
        if (embeddingModel == null) embeddingModel = "text-embedding-v4";
    }
}
