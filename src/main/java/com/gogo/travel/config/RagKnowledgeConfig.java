package com.gogo.travel.config;
import com.gogo.travel.knowledge.*;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.model.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.*;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(PgVectorProperties.class)
public class RagKnowledgeConfig {
    private static final org.slf4j.Logger logger=org.slf4j.LoggerFactory.getLogger(RagKnowledgeConfig.class);
    @Bean public PgVectorRepository knowledgeRepository(PgVectorProperties p) {
        PgVectorRepository repository=PgVectorRepository.create(p);
        if(p.initializeSchema()) repository.initialize();
        return repository;
    }
    @Bean public EmbeddingModel knowledgeEmbedding(PgVectorProperties p, @Value("${agentscope.dashscope.api-key}") String key) {
        return DashScopeTextEmbedding.builder().apiKey(key).modelName(p.embeddingModel()).dimensions(p.dimensions()).build();
    }
    @Bean public PgVectorKnowledge attractionKnowledge(PgVectorRepository r, EmbeddingModel knowledgeEmbedding) {
        return new PgVectorKnowledge("attractions",r,knowledgeEmbedding);
    }
    @Bean public PgVectorKnowledge corporateTravelPolicyKnowledge(PgVectorRepository r, EmbeddingModel knowledgeEmbedding) {
        return new PgVectorKnowledge("policy",r,knowledgeEmbedding);
    }
    @Bean public PgVectorKnowledge corporateTravelGuidelinesKnowledge(PgVectorRepository r, EmbeddingModel knowledgeEmbedding) {
        return new PgVectorKnowledge("guidelines",r,knowledgeEmbedding);
    }
    @Bean public ApplicationRunner importKnowledge(PgVectorProperties p, PgVectorRepository repository, EmbeddingModel knowledgeEmbedding) {
        return args -> {
            if(!p.importOnStartup() && !args.containsOption("import-knowledge")) return;
            String[][] sources={{"attractions","tourist_attraction.xlsx"},{"policy","business_travel_policy.docx"},{"guidelines","business_travel_guidelines.docx"}};
            for(String[] source:sources) {
                Resource resource=new ClassPathResource("dataset/"+source[1]);
                String revision=KnowledgeImporter.revision(resource,p.embeddingModel(),p.dimensions());
                if(repository.isCurrent(source[0],revision)) {
                    logger.info("Knowledge corpus {} already current; no embedding calls needed",source[0]);
                    continue;
                }
                List<Document> documents=KnowledgeImporter.read(resource,source[0]);
                logger.info("Importing knowledge corpus {}, chunks={}",source[0],documents.size());
                new PgVectorKnowledge(source[0],repository,knowledgeEmbedding)
                    .importCorpus(revision,documents).block(Duration.ofMinutes(30));
                logger.info("Knowledge corpus {} imported successfully",source[0]);
            }
        };
    }
    static List<Document> readWordDocuments(Resource resource) throws IOException { return KnowledgeImporter.readWord(resource); }
}
