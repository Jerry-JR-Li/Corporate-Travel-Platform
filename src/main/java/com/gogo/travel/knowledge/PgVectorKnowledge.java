package com.gogo.travel.knowledge;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.List;

/** A persistent corpus-scoped Knowledge adapter for AgentScope's retrieve_knowledge tool. */
public class PgVectorKnowledge implements Knowledge {
    private final String corpus;
    private final PgVectorRepository repository;
    private final EmbeddingModel embedding;
    public PgVectorKnowledge(String corpus, PgVectorRepository repository, EmbeddingModel embedding) {
        this.corpus=corpus; this.repository=repository; this.embedding=embedding;
    }
    public Mono<Void> importCorpus(String revision, List<Document> documents) {
        if (documents.isEmpty()) return Mono.error(new IllegalArgumentException("Empty corpus refused"));
        return Mono.fromCallable(() -> repository.isCurrent(corpus,revision)).subscribeOn(Schedulers.boundedElastic())
            .flatMap(current -> current ? Mono.empty() : embedDocuments(documents)
                .flatMap(ready -> Mono.<Void>fromRunnable(() -> repository.replaceCorpus(corpus,revision,ready))
                    .subscribeOn(Schedulers.boundedElastic())));
    }
    @Override public Mono<Void> addDocuments(List<Document> documents) {
        if (documents.isEmpty()) return Mono.empty();
        return embedDocuments(documents).flatMap(ready -> Mono.<Void>fromRunnable(() -> repository.addDocuments(corpus,ready))
            .subscribeOn(Schedulers.boundedElastic()));
    }
    private Mono<List<Document>> embedDocuments(List<Document> documents) {
        return Flux.fromIterable(documents)
            .flatMap(doc -> embedding.embed(doc.getMetadata().getContent()).timeout(Duration.ofSeconds(45))
                .map(vector -> { repository.vector(vector); doc.setEmbedding(vector); return doc; }),4).collectList();
    }
    @Override public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null || query.isBlank()) return Mono.just(List.of());
        return embedding.embed(TextBlock.builder().text(query).build()).timeout(Duration.ofSeconds(30))
            .flatMap(vector -> Mono.fromCallable(() -> repository.search(corpus,vector,config.getLimit(),config.getScoreThreshold()))
                .subscribeOn(Schedulers.boundedElastic()));
    }
}
