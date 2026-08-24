package com.asm.assistant.tools;

import com.asm.assistant.tools.LiveToolCatalog.Tool;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standby path: what the assistant can still answer with every LLM provider down. Pure logic — no
 * Spring, no network, no model.
 */
class LexicalToolSelectorTest {

    private final LexicalToolSelector selector = new LexicalToolSelector(new LiveToolCatalog());

    private String chosen(String question) {
        return selector.select(question).map(Tool::name).orElse("NONE");
    }

    @Test
    void aggregateQuestions_reachTheirToolWithoutTheModel() {
        assertThat(chosen("combien de retours j'ai ?")).isEqualTo("returns_kpi");
        assertThat(chosen("combien de livraisons aujourd'hui ?")).isEqualTo("deliveries_counts");
        assertThat(chosen("quels sont les depots actifs ?")).isEqualTo("depots_active");
        assertThat(chosen("quels livreurs sont disponibles ?")).isEqualTo("drivers_available");
        assertThat(chosen("combien d'argent est en circulation ?")).isEqualTo("cash_circulation");
    }

    /** The reason the lexicon is folded: users type without accents, and did in the reported bug. */
    @Test
    void accentsAndCaseDoNotChangeTheChoice() {
        assertThat(chosen("Combien de RETOURS ?")).isEqualTo("returns_kpi");
        assertThat(chosen("quels sont les dépôts actifs ?")).isEqualTo("depots_active");
        assertThat(chosen("état des livreurs")).isEqualTo("drivers_stats");
    }

    /** A wrong endpoint answered confidently is worse than falling through to the corpus. */
    @Test
    void conceptualQuestions_selectNothing() {
        assertThat(chosen("comment fonctionne la synchronisation ERP ?")).isEqualTo("NONE");
        assertThat(chosen("c'est quoi asm track")).isEqualTo("NONE");
        assertThat(chosen("quelles sont les phases du SLA ?")).isEqualTo("NONE");
    }

    @Test
    void entityTools_areNeverReachable_becauseAnIdCannotBeGuessed() {
        assertThat(selector.select("statut de la livraison D-1234"))
                .isEmpty();
        assertThat(new LiveToolCatalog().all().stream().filter(Tool::needsId))
                .allSatisfy(t -> assertThat(t.triggers()).isEmpty());
    }

    @Test
    void emptyInput_isNotAChoice() {
        assertThat(selector.select(null)).isEmpty();
        assertThat(selector.select("   ")).isEmpty();
    }

    /** The longest matching phrase wins, so a specific question beats a tool sharing its vocabulary. */
    @Test
    void longerPhraseWins() {
        assertThat(chosen("quelle est la derniere synchronisation erp ?")).isEqualTo("erp_sync_history");
    }
}
