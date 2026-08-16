package com.asm.erpadapter.mapping.type;

import com.asm.erpadapter.mapping.CanonicalField;
import com.asm.erpadapter.mapping.ErpFieldCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Judges whether a proposed mapping can actually fill the field it targets.
 *
 * <p>Answers the same question for the picker (which greys out what will be refused) and for the save
 * (which refuses it), from the same source — the registered converters. A screen that computed its own
 * verdict would eventually disagree with the save, and the integrator would be told no by a dialog
 * after being told yes by a dropdown.
 *
 * <p>Resolving the source type needs the field's declared type from the ERP, which only the
 * {@link ErpFieldCatalog} can give. When it cannot be reached — the ERP is down, the model is
 * unknown — the verdict is {@link Compatibility#LOSSY} rather than a refusal: blocking configuration
 * because an ERP is briefly unavailable is worse than accepting a mapping we could not verify, and
 * the preview still runs the real conversion on real records.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MappingTypeChecker {

    private final CanonicalConverterRegistry registry;
    private final List<ErpFieldCatalog> catalogs;

    /** What a mapping onto {@code sourcePath} would do to {@code field}. */
    public record Verdict(Compatibility compatibility, SourceType sourceType, String message) {

        public boolean isRefused() {
            return compatibility == Compatibility.UNSUPPORTED;
        }
    }

    /**
     * @param provider   lowercase provider key
     * @param field      the canonical field being filled, or null for a customer-defined extra
     * @param model      the document the last path segment belongs to
     * @param fieldName  the last path segment — the field actually being read
     */
    public Verdict check(String provider, CanonicalField field, String model, String fieldName) {
        // An extra has no canonical type: it is carried through as-is into the custom_fields bag, so
        // there is nothing to be incompatible with.
        if (field == null) {
            return new Verdict(Compatibility.SAFE, null, null);
        }

        SourceType source = resolveSourceType(provider, model, fieldName);
        if (source == null) {
            log.debug("Type check: could not resolve the type of {}.{} on {}", model, fieldName, provider);
            return new Verdict(Compatibility.LOSSY, null,
                    "Le type du champ source n'a pas pu être vérifié auprès de l'ERP.");
        }

        Compatibility c = registry.compatibility(field, source);
        return new Verdict(c, source, message(field, source, c));
    }

    /** The normalised type of one ERP field, or null when the catalogue cannot say. */
    public SourceType resolveSourceType(String provider, String model, String fieldName) {
        if (provider == null || model == null || fieldName == null) return null;
        ErpFieldCatalog catalog = catalogs.stream()
                .filter(c -> c.provider().equalsIgnoreCase(provider))
                .findFirst().orElse(null);
        if (catalog == null) return null;

        try {
            return catalog.fieldsOf(model).stream()
                    .filter(f -> f.name().equals(fieldName))
                    .findFirst()
                    .map(f -> catalog.normalize(f.type()))
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Type check: catalogue unreachable for {}.{}: {}", model, fieldName, e.getMessage());
            return null;
        }
    }

    /** Wording aimed at the integrator, not at a developer — it is shown in the mapping screen. */
    private String message(CanonicalField field, SourceType source, Compatibility c) {
        return switch (c) {
            case SAFE -> null;
            case LOSSY -> lossyReason(field, source);
            case UNSUPPORTED -> "Le champ ASM " + field.name() + " attend "
                    + humanTarget(field.type()) + " ; ce champ ERP est de type " + human(source) + ".";
        };
    }

    private String lossyReason(CanonicalField field, SourceType source) {
        return switch (field.type()) {
            case INTEGER -> source == SourceType.DECIMAL
                    ? "Les décimales seront perdues : 2,5 deviendra 3."
                    : "Conversion possible mais approximative.";
            case DATE_TIME -> switch (source) {
                case DATE -> "Ce champ ne porte pas d'heure : l'heure sera fixée à 00:00.";
                case TEXT -> "Ce champ est du texte libre : une valeur mal formée sera signalée à l'import.";
                default -> "Conversion possible mais approximative.";
            };
            case TEXT -> switch (source) {
                case ENUM -> "La valeur technique sera enregistrée (par ex. « assigned »), pas le libellé affiché dans l'ERP.";
                case RELATION -> "Seul le libellé du lien sera enregistré, pas son identifiant.";
                case RICH_TEXT -> "La mise en forme HTML sera conservée telle quelle.";
                default -> "Conversion possible mais approximative.";
            };
            case BOOLEAN -> "La valeur sera interprétée comme vraie seulement pour un ensemble connu de mots (oui, true, 1, assigned, ready, done).";
            default -> "Conversion possible mais approximative.";
        };
    }

    private static String humanTarget(CanonicalType t) {
        return switch (t) {
            case TEXT -> "du texte";
            case INTEGER -> "un nombre entier";
            case DECIMAL -> "un montant";
            case BOOLEAN -> "une valeur oui/non";
            case DATE_TIME -> "une date";
        };
    }

    private static String human(SourceType s) {
        return switch (s) {
            case TEXT -> "texte";
            case RICH_TEXT -> "texte enrichi";
            case ENUM -> "liste de choix";
            case RELATION -> "lien vers un autre document";
            case INTEGER -> "nombre entier";
            case DECIMAL -> "nombre décimal";
            case BOOLEAN -> "oui/non";
            case DATE -> "date";
            case DATE_TIME -> "date et heure";
            case UNKNOWN -> "inconnu";
        };
    }
}
