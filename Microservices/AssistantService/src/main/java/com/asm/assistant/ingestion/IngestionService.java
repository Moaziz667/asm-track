package com.asm.assistant.ingestion;

import com.asm.assistant.domain.port.EmbeddingPort;
import com.asm.assistant.domain.port.VectorStorePort;
import com.asm.assistant.domain.port.VectorStorePort.UpsertResult;
import com.asm.assistant.ingestion.model.ParsedChunk;
import com.asm.assistant.ingestion.model.SourceFile;
import com.asm.assistant.ingestion.parser.MarkdownParser;
import com.asm.assistant.ingestion.parser.OpenApiParser;
import com.asm.assistant.persistence.RagDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * The ingestion lifecycle for one run: discover → parse/chunk → embed → persist (idempotent).
 *
 * <p>All corpus documents are tagged with the {@code GLOBAL} tenant — architecture, ADRs, API and
 * business docs are platform-wide knowledge every tenant may read; per-tenant documents would set a
 * real company id here. Freshness is a content checksum: an unchanged file is skipped, a changed one
 * supersedes its prior version (see {@link com.asm.assistant.adapter.store.PgVectorStore}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    private final SourceDiscovery discovery;
    private final MarkdownParser markdownParser;
    private final OpenApiParser openApiParser;
    private final EmbeddingPort embedding;
    private final VectorStorePort store;

    public Report ingestAll() {
        List<SourceFile> sources = discovery.discover();
        int inserted = 0, updated = 0, unchanged = 0, chunks = 0;
        for (SourceFile src : sources) {
            List<ParsedChunk> parsed = switch (src.type()) {
                case MARKDOWN -> markdownParser.parse(src.content());
                case OPENAPI -> openApiParser.parse(src.content());
            };
            if (parsed.isEmpty()) continue;

            List<String> texts = parsed.stream().map(ParsedChunk::content).toList();
            List<float[]> vectors = embedding.isEnabled() ? embedding.embed(texts) : null;

            UpsertResult r = store.upsertDocument(
                    src.externalId(), src.type().name().toLowerCase(), src.path(), titleOf(src),
                    src.authority(), RagDocument.GLOBAL_TENANT, checksum(src.content()),
                    parsed, vectors, embedding.modelVersion());

            switch (r) {
                case INSERTED -> { inserted++; chunks += parsed.size(); }
                case UPDATED -> { updated++; chunks += parsed.size(); }
                case UNCHANGED -> unchanged++;
            }
        }
        Report report = new Report(sources.size(), inserted, updated, unchanged, chunks, embedding.isEnabled());
        log.info("Ingestion done: {}", report);
        return report;
    }

    private String titleOf(SourceFile src) {
        String name = src.externalId();
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private String checksum(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Report(int sources, int inserted, int updated, int unchanged, int chunks, boolean embedded) {}
}
