package com.rupeshagrahari.agenticrag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class IngestionService {

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final TenantRegistryService tenantRegistryService;

    public IngestionService(VectorStore vectorStore, RagProperties ragProperties,
            TenantRegistryService tenantRegistryService) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.tenantRegistryService = tenantRegistryService;
    }

    public int ingest(String tenantId) {
        Path tenantDir = Path.of(ragProperties.ingestion().docsLocation()).resolve(tenantId);
        if (!Files.isDirectory(tenantDir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No docs folder for tenant '" + tenantId + "' at " + tenantDir);
        }

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(ragProperties.ingestion().chunkSize())
                .build();

        int totalChunks = 0;
        for (Path file : listDocumentFiles(tenantDir)) {
            String source = tenantDir.relativize(file).toString();
            List<Document> parentDocuments = new TextReader(new FileSystemResource(file)).get();
            List<Document> chunks = splitter.apply(parentDocuments);

            List<Document> chunksWithDeterministicIds = new ArrayList<>();
            for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
                chunksWithDeterministicIds.add(chunks.get(chunkIndex)
                        .mutate()
                        .id(deterministicId(tenantId, source, chunkIndex))
                        .metadata("tenantId", tenantId)
                        .metadata("source", source)
                        .metadata("chunkIndex", chunkIndex)
                        .build());
            }

            vectorStore.add(chunksWithDeterministicIds);
            totalChunks += chunksWithDeterministicIds.size();
        }

        // Register-on-write: this path just created tenant-scoped data, so the tenant
        // must be a known, ACTIVE row in the registry the /chat guardrail checks. Runs
        // after a successful ingest only -- the 404 above (no docs folder) returns before
        // this point, so a nonexistent tenant is never registered.
        tenantRegistryService.registerActive(tenantId);
        return totalChunks;
    }

    public void deleteTenant(String tenantId) {
        vectorStore.delete(new FilterExpressionBuilder().eq("tenantId", tenantId).build());
    }

    private List<Path> listDocumentFiles(Path tenantDir) {
        try (Stream<Path> stream = Files.list(tenantDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to list documents in " + tenantDir, e);
        }
    }

    // Deterministic across re-ingestion runs: same tenant + source + chunk index always
    // yields the same id, so vectorStore.add (an upsert) overwrites rather than duplicates.
    private String deterministicId(String tenantId, String source, int chunkIndex) {
        String key = tenantId + "#" + source + "#" + chunkIndex;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

}
