package com.gogo.travel.knowledge;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Runs against a real pgvector database; touches only random test-prefixed corpora. */
@EnabledIfEnvironmentVariable(named="PGVECTOR_TEST_URL",matches=".+")
class PgVectorIntegrationTest {
    PgVectorRepository repository;
    JdbcTemplate jdbc;
    String corpus="test-"+UUID.randomUUID(), other=corpus+"-other";
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource(System.getenv("PGVECTOR_TEST_URL"),System.getenv("PGVECTOR_TEST_USER"),System.getenv("PGVECTOR_TEST_PASSWORD"));
        jdbc=new JdbcTemplate(source); repository=new PgVectorRepository(source,1024); repository.initialize();
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM travel_knowledge_chunk WHERE corpus IN (?,?)",corpus,other);
        jdbc.update("DELETE FROM travel_knowledge_corpus WHERE corpus IN (?,?)",corpus,other);
    }
    double[] vector(int index) { double[] v=new double[1024]; v[index]=1; return v; }
    Document doc(String id,String text,int index) {
        var doc=new Document(new DocumentMetadata(TextBlock.builder().text(text).build(),corpus,id,Map.of("source","test")));
        doc.setEmbedding(vector(index)); return doc;
    }
    @Test void cosineSearchIsPersistentScopedAndImportIsAtomicAndIdempotent() {
        repository.replaceCorpus(corpus,"v1",List.of(doc("a","window",0),doc("b","aisle",1)));
        repository.replaceCorpus(other,"v1",List.of(doc("a","other user corpus",0)));
        assertThat(repository.search(corpus,vector(0),10,0.8)).extracting(d -> d.getMetadata().getContentText()).containsExactly("window");
        assertThat(repository.isCurrent(corpus,"v1")).isTrue();
        repository.replaceCorpus(corpus,"v1",List.of(doc("c","must not replace",0)));
        assertThat(repository.search(corpus,vector(0),10,0.8)).extracting(d -> d.getMetadata().getContentText()).containsExactly("window");
        // Duplicate IDs fail after DELETE/first INSERT; the transaction must restore the old corpus.
        assertThatThrownBy(() -> repository.replaceCorpus(corpus,"v2",List.of(doc("x","bad",0),doc("x","duplicate",1))))
            .isInstanceOf(RuntimeException.class);
        assertThat(repository.isCurrent(corpus,"v1")).isTrue();
        assertThat(repository.search(corpus,vector(0),10,0.8)).extracting(d -> d.getMetadata().getContentText()).containsExactly("window");
        repository.replaceCorpus(corpus,"v3",List.of(doc("c","updated",0)));
        assertThat(repository.isCurrent(corpus,"v3")).isTrue();
        repository.addDocuments(corpus,List.of(doc("d","appended",1)));
        assertThat(repository.search(corpus,vector(1),10,0.8)).extracting(d -> d.getMetadata().getContentText()).containsExactly("appended");
        assertThat(repository.search(corpus,vector(0),10,0.8)).extracting(d -> d.getMetadata().getContentText()).containsExactly("updated");
        var incompatible = new PgVectorRepository(jdbc.getDataSource(),1024,"different-model");
        assertThatThrownBy(() -> incompatible.search(corpus,vector(0),10,0)).isInstanceOf(IllegalStateException.class);
        assertThat(repository.vector(vector(0))).startsWith("[1.0,0.0");
        assertThatThrownBy(() -> repository.vector(new double[3])).isInstanceOf(IllegalArgumentException.class);
        var invalid=vector(0); invalid[2]=Double.NaN;
        assertThatThrownBy(() -> repository.vector(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
}
