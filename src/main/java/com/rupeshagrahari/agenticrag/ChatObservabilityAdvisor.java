package com.rupeshagrahari.agenticrag;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.core.Ordered;

/**
 * M4 part 3: observability, not behavior. Attached to every generation call
 * (RAG/MCP/BOTH) purely to log -- it never mutates the request or response, and DENY
 * never reaches it (generation is skipped entirely for DENY, so there is nothing here to
 * observe).
 * <p>
 * Ordered {@code Ordered.LOWEST_PRECEDENCE - 1} so it is the innermost advisor: Spring AI
 * sorts advisors ascending by order and runs {@code before()} in that order (lower =
 * outer = runs first), so by the time this advisor's {@code before()} runs, both
 * {@link QuestionAnswerAdvisor} (default order {@code -2147482648}, if attached this
 * turn) and the chat-memory advisor (same default) have already run -- retrieved
 * documents are in context and the request's prompt is the fully assembled one about to
 * be sent to the model. Deliberately one less than {@code Ordered.LOWEST_PRECEDENCE}
 * (not equal to it): Spring AI's own terminal advisors that actually invoke the model
 * ({@code ChatModelCallAdvisor} / {@code ChatModelStreamAdvisor}) are hardcoded to
 * exactly {@code Ordered.LOWEST_PRECEDENCE} -- using that same sentinel value here
 * silently prevented this advisor's {@code before()}/{@code after()} from ever being
 * invoked (verified empirically: swapping to {@code LOWEST_PRECEDENCE - 1} is what fixed
 * it).
 */
public class ChatObservabilityAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ChatObservabilityAdvisor.class);

    private final String correlationId;
    private final String tenantId;

    public ChatObservabilityAdvisor(String correlationId, String tenantId) {
        this.correlationId = correlationId;
        this.tenantId = tenantId;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        logRetrievalIfPresent(request);

        // Verbose and gated behind DEBUG: the full prompt includes the user's raw
        // message and any RAG-injected document text, unlike the INFO-level fields
        // elsewhere in this class.
        //
        // Built from getInstructions() (each message's role + text), not
        // Prompt.getContents(): getContents() was verified empirically to NOT reflect
        // QuestionAnswerAdvisor's augmented user message here (it returned the bare
        // 54-character question even though the actual outgoing message was 5,485
        // characters, confirmed via getInstructions()) -- a real discrepancy in this
        // Spring AI version, not a formatting preference.
        if (log.isDebugEnabled()) {
            // Newlines escaped to "\n" literal so this stays one physical log line --
            // otherwise a multi-paragraph RAG-augmented prompt splits the entry across
            // many lines, breaking naive line-oriented log parsing/grep.
            String assembledPrompt = request.prompt()
                    .getInstructions()
                    .stream()
                    .map(m -> m.getMessageType() + ": " + m.getText().replace("\n", "\\n"))
                    .collect(Collectors.joining(" | "));
            log.debug("event=final_prompt correlationId={} tenantId={} prompt={}", correlationId, tenantId,
                    assembledPrompt);
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    // No-op when RAG wasn't attached this turn (nothing under this key in context) --
    // this is what makes "retrieval details (when RAG ran)" conditional purely on data
    // presence, rather than needing its own path check duplicated here.
    private void logRetrievalIfPresent(ChatClientRequest request) {
        Object retrieved = request.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(retrieved instanceof List<?> documents)) {
            return;
        }

        log.info("event=retrieval_summary correlationId={} tenantId={} chunkCount={}", correlationId, tenantId,
                documents.size());

        for (Object item : documents) {
            if (!(item instanceof Document document)) {
                continue;
            }
            // Ids, tenant, source, and similarity score only -- enough to confirm
            // retrieval was tenant-scoped and see what was pulled, without the chunk
            // body itself landing at INFO.
            log.info(
                    "event=retrieval_chunk correlationId={} tenantId={} chunkId={} chunkTenantId={} source={} score={}",
                    correlationId, tenantId, document.getId(), document.getMetadata().get("tenantId"),
                    document.getMetadata().get("source"), document.getScore());

            if (log.isDebugEnabled()) {
                log.debug("event=retrieval_chunk_text correlationId={} chunkId={} text={}", correlationId,
                        document.getId(), document.getText().replace("\n", "\\n"));
            }
        }
    }

}
