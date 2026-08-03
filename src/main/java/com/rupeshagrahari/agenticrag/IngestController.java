package com.rupeshagrahari.agenticrag;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IngestController {

    private final IngestionService ingestionService;

    public IngestController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest/{tenantId}")
    public Map<String, Object> ingest(@PathVariable String tenantId) {
        int chunksIngested = ingestionService.ingest(tenantId);
        return Map.of("status", "OK", "tenantId", tenantId, "chunksIngested", chunksIngested);
    }

    @DeleteMapping("/ingest/{tenantId}")
    public Map<String, Object> deleteTenant(@PathVariable String tenantId) {
        ingestionService.deleteTenant(tenantId);
        return Map.of("status", "OK", "tenantId", tenantId);
    }

}
