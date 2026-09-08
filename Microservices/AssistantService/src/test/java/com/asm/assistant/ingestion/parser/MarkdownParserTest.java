package com.asm.assistant.ingestion.parser;

import com.asm.assistant.ingestion.model.ParsedChunk;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le découpage des documents Markdown avant indexation.
 *
 * <p>Ce que ces tests protègent : l'assistant ne répond qu'à partir des fragments qu'il retrouve, et
 * cite le titre du fragment comme source. Un découpage qui perd le fil des titres produit donc des
 * citations fausses, et un découpage qui coupe au milieu d'un bloc de code retourne un extrait
 * inexploitable. Rien de tout cela ne lève d'exception : la réponse est simplement moins bonne.
 */
class MarkdownParserTest {

    private MarkdownParser parser(int maxChars, int overlapChars) {
        var p = new MarkdownParser();
        ReflectionTestUtils.setField(p, "maxChars", maxChars);
        ReflectionTestUtils.setField(p, "overlapChars", overlapChars);
        return p;
    }

    private MarkdownParser parser() {
        return parser(2000, 150);
    }

    // ── Découpage par titre ─────────────────────────────────────────────────

    @Test
    void splitsOnHeadingsSoEachChunkIsOneSection() {
        var chunks = parser().parse("""
                # Guide
                Introduction.

                ## Livraisons
                Comment créer une livraison.

                ## Tournées
                Comment planifier une tournée.
                """);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(ParsedChunk::ordinal).containsExactly(0, 1, 2);
    }

    /** La citation doit porter le fil complet : un « Livraisons » seul ne situe rien. */
    @Test
    void labelsEachChunkWithItsHeadingTrail() {
        var chunks = parser().parse("""
                # Guide
                Intro.

                ## Livraisons
                Corps.
                """);

        assertThat(chunks).extracting(ParsedChunk::section)
                .containsExactly("Guide", "Guide › Livraisons");
    }

    @Test
    void doesNotRepeatTheTitleWhenTheSectionIsTheDocumentItself() {
        var chunks = parser().parse("# Guide\nCorps du guide.\n");

        assertThat(chunks).singleElement()
                .extracting(ParsedChunk::section).isEqualTo("Guide");
    }

    /** Le titre est repris en tête du fragment, mais dépouillé de ses dièses. */
    @Test
    void keepsTheHeadingInsideTheChunkContent() {
        var chunks = parser().parse("# Guide\nCorps.\n");

        assertThat(chunks.get(0).content()).startsWith("Guide").contains("Corps.");
    }

    @Test
    void ignoresHeadingsDeeperThanLevelThree() {
        var chunks = parser().parse("""
                # Guide
                Intro.

                #### Détail
                Un détail qui reste dans la section précédente.
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("#### Détail");
    }

    // ── Les blocs de code ne sont jamais coupés ─────────────────────────────

    /**
     * Un « # » en première colonne d'un bloc shell est un commentaire, pas un titre. Sans le suivi
     * des clôtures, le fragment était coupé en plein milieu d'un exemple de commande.
     */
    @Test
    void neverTreatsAHashInsideACodeFenceAsAHeading() {
        var chunks = parser().parse("""
                # Guide
                Voici la commande :

                ```bash
                # installe les dépendances
                npm ci
                ```

                Fin.
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("npm ci").contains("# installe les dépendances");
    }

    // ── Frontmatter ─────────────────────────────────────────────────────────

    @Test
    void stripsTheYamlFrontmatter() {
        var chunks = parser().parse("""
                ---
                title: Guide interne
                tags: [rag]
                ---
                # Guide
                Corps.
                """);

        assertThat(chunks.get(0).content()).doesNotContain("tags:").doesNotContain("title:");
        assertThat(chunks.get(0).content()).contains("Corps.");
    }

    /**
     * Trois tirets sans clôture sont une ligne horizontale, pas un frontmatter. Rien n'est retiré~:
     * les tirets forment un premier fragment sans titre, et le corps reste intact dans le suivant.
     */
    @Test
    void leavesAnUnterminatedFrontmatterAlone() {
        var chunks = parser().parse("---\n# Guide\nCorps.\n");

        assertThat(chunks).extracting(ParsedChunk::content)
                .anyMatch(c -> c.contains("Corps."));
        assertThat(chunks.get(0).content()).isEqualTo("---");
    }

    // ── Normalisation des fins de ligne ─────────────────────────────────────

    /**
     * Les documents sont extraits du dépôt avec des fins de ligne Windows. Un retour chariot résiduel
     * empêchait la détection des titres et repliait le fichier entier en un seul fragment.
     */
    @Test
    void detectsHeadingsEvenWhenTheFileUsesWindowsLineEndings() {
        var chunks = parser().parse("# Guide\r\nIntro.\r\n\r\n## Livraisons\r\nCorps.\r\n");

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(ParsedChunk::section)
                .containsExactly("Guide", "Guide › Livraisons");
    }

    // ── Découpage des sections trop longues ─────────────────────────────────

    @Test
    void leavesASectionIntactWhenItFitsInTheBudget() {
        var chunks = parser(2000, 150).parse("# Guide\nCourt.\n");

        assertThat(chunks).hasSize(1);
    }

    @Test
    void subSplitsAnOversizedSectionOnParagraphBoundaries() {
        String paragraphe = "x".repeat(80);
        String corps = String.join("\n\n", java.util.Collections.nCopies(10, paragraphe));

        var chunks = parser(200, 20).parse("# Guide\n" + corps + "\n");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(ParsedChunk::section)
                .allMatch("Guide"::equals);
    }

    /** Les fragments issus d'une même section restent numérotés dans l'ordre de lecture. */
    @Test
    void numbersEveryChunkSequentiallyAcrossSections() {
        String corps = String.join("\n\n", java.util.Collections.nCopies(8, "y".repeat(80)));

        var chunks = parser(200, 20).parse("# Guide\n" + corps + "\n\n## Suite\nFin.\n");

        assertThat(chunks).extracting(ParsedChunk::ordinal)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }

    // ── Cas dégénérés ───────────────────────────────────────────────────────

    @Test
    void returnsNothingForAnEmptyDocument() {
        assertThat(parser().parse("")).isEmpty();
        assertThat(parser().parse("   \n\n  \n")).isEmpty();
    }

    @Test
    void handlesADocumentWithNoHeadingAtAll() {
        var chunks = parser().parse("Juste du texte, sans le moindre titre.\n");

        assertThat(chunks).singleElement()
                .extracting(ParsedChunk::section).isEqualTo("");
    }

    @Test
    void stripsSurroundingWhitespaceFromEveryChunk() {
        var chunks = parser().parse("# Guide\n\n\nCorps.\n\n\n");

        assertThat(chunks.get(0).content()).isEqualTo(chunks.get(0).content().strip());
    }
}
