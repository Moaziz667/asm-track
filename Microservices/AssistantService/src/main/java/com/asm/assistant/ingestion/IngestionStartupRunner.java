package com.asm.assistant.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Optional startup ingestion, off by default. Enabled for local bring-up / CI corpus fixtures via
 * {@code assistant.ingestion.run-on-startup=true}; production ingestion is triggered explicitly
 * (admin endpoint) or, later, on a schedule.
 */
@Component
@ConditionalOnProperty(name = "assistant.ingestion.run-on-startup", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class IngestionStartupRunner implements ApplicationRunner {

    private final IngestionService ingestionService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Startup ingestion enabled — ingesting corpus…");
        ingestionService.ingestAll();
    }
}
