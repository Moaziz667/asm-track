package com.asm.assistant.ingestion;

import com.asm.assistant.ingestion.model.SourceFile;
import com.asm.assistant.ingestion.model.SourceFile.SourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks the mounted corpus root, applies {@link SourcePolicy}, and returns the source files worth
 * indexing. Markdown everywhere under the root; OpenAPI only the JSON contracts under {@code openapi/}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SourceDiscovery {

    private final SourcePolicy policy;

    @Value("${assistant.ingestion.root:/corpus}")
    private String root;

    public List<SourceFile> discover() {
        Path base = Paths.get(root);
        if (!Files.isDirectory(base)) {
            log.warn("Ingestion root {} is not a directory — nothing to ingest", base);
            return List.of();
        }
        List<SourceFile> out = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(base)) {
            paths.filter(Files::isRegularFile).forEach(p -> {
                String rel = base.relativize(p).toString().replace('\\', '/');
                SourceType type = typeOf(rel);
                if (type == null || policy.isExcluded(rel)) return;
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    out.add(new SourceFile(rel, type, p.toString(),
                            policy.authorityFor(rel, type), content));
                } catch (IOException e) {
                    log.warn("Skipping unreadable source {}: {}", rel, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("Failed walking corpus root {}: {}", base, e.getMessage(), e);
        }
        log.info("Discovered {} indexable source(s) under {}", out.size(), base);
        return out;
    }

    private SourceType typeOf(String rel) {
        String low = rel.toLowerCase();
        if (low.endsWith(".md")) return SourceType.MARKDOWN;
        if (low.startsWith("openapi/") && low.endsWith(".json")) return SourceType.OPENAPI;
        return null;
    }
}
