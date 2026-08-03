package com.rupeshagrahari.agenticrag;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal retrieval eval harness (M2 part 2). Requires the real dev stack running
 * (Postgres via docker compose, Ollama with nomic-embed-text) -- this is a retrieval-only
 * integration check, not a fast unit test. Run with: ./mvnw test -Dtest=RagEvalTest
 *
 * "precision@k" here is a hit-rate: fraction of eval queries where the expected chunk's
 * source file appears somewhere in the tenant-filtered top-K results.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagEvalTest {

    private record EvalCase(String tenantId, String query, String expectedSource) {
    }

    private static final List<EvalCase> EVAL_CASES = List.of(
            new EvalCase("acme", "What is the per-diem limit for international travel?", "travel-expense-policy.md"),
            new EvalCase("acme", "What happens if I lose my corporate card?", "card-program-guide.md"),
            new EvalCase("acme", "What countries do we support?", "country-support.md"),
            new EvalCase("globex", "What is the per-diem limit for international travel?", "travel-expense-policy.md"),
            new EvalCase("globex", "What are the corporate card spend limits?", "card-program-guide.md"));

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private RagProperties ragProperties;

    @BeforeAll
    void ingestBothTenants() {
        ingestionService.ingest("acme");
        ingestionService.ingest("globex");
    }

    @Test
    void precisionAtKAcrossTenants() {
        int topK = ragProperties.retrieval().topK();
        int hits = 0;

        for (EvalCase evalCase : EVAL_CASES) {
            List<Document> results = search(evalCase.query(), evalCase.tenantId(), topK);

            // Every result must belong to the requested tenant -- checked for every case,
            // not just the dedicated cross-tenant test below.
            assertThat(results).allSatisfy(
                    doc -> assertThat(doc.getMetadata().get("tenantId")).isEqualTo(evalCase.tenantId()));

            boolean hit = results.stream()
                    .anyMatch(doc -> evalCase.expectedSource().equals(doc.getMetadata().get("source")));
            if (hit) {
                hits++;
            }
            else {
                System.out.printf("MISS tenant=%s query=\"%s\" expected=%s retrieved=%s%n", evalCase.tenantId(),
                        evalCase.query(), evalCase.expectedSource(),
                        results.stream().map(d -> d.getMetadata().get("source")).toList());
            }
        }

        double precisionAtK = (double) hits / EVAL_CASES.size();
        System.out.printf("precision@%d = %d/%d = %.2f%n", topK, hits, EVAL_CASES.size(), precisionAtK);

        assertThat(precisionAtK).isEqualTo(1.0);
    }

    @Test
    void crossTenantQueryNeverLeaksTheOtherTenantsChunks() {
        int topK = ragProperties.retrieval().topK();
        String sharedQuery = "What is the per-diem limit for international travel?";

        List<Document> acmeResults = search(sharedQuery, "acme", topK);
        List<Document> globexResults = search(sharedQuery, "globex", topK);

        assertThat(acmeResults).isNotEmpty();
        assertThat(globexResults).isNotEmpty();

        List<String> acmeIds = acmeResults.stream().map(Document::getId).toList();
        List<String> globexIds = globexResults.stream().map(Document::getId).toList();

        assertThat(acmeIds).doesNotContainAnyElementsOf(globexIds);
        System.out.printf(
                "Cross-tenant isolation check passed: same query, acme (%d results) and globex (%d results) chunk sets are disjoint.%n",
                acmeIds.size(), globexIds.size());
    }

    private List<Document> search(String query, String tenantId, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(new FilterExpressionBuilder().eq("tenantId", tenantId).build())
                .build());
    }

}
