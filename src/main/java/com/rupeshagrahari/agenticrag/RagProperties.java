package com.rupeshagrahari.agenticrag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(Ingestion ingestion, Retrieval retrieval) {

    public record Ingestion(String docsLocation, int chunkSize) {
    }

    public record Retrieval(int topK, double similarityThreshold) {
    }

}
