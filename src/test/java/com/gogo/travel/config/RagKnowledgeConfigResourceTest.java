package com.gogo.travel.config;

import io.agentscope.core.rag.model.Document;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RagKnowledgeConfigResourceTest {

    @Test
    void readsWordDocumentsWithoutAFileSystemPath() throws IOException {
        ClassPathResource source = new ClassPathResource("dataset/business_travel_policy.docx");
        ByteArrayResource nestedJarLikeResource = new ByteArrayResource(source.getInputStream().readAllBytes());

        List<Document> documents = RagKnowledgeConfig.readWordDocuments(nestedJarLikeResource);

        assertFalse(documents.isEmpty());
    }
}
