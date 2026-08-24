package com.asm.assistant.tools;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The closed set of live reads the assistant may perform — an allow-list, not a capability the model
 * can extend. The LLM only ever picks a {@code name} from this catalogue; it never sees or composes a
 * URL, so it cannot reach an endpoint that is not declared here, cannot add query parameters, and
 * cannot turn a read into a write. Every entry is a GET.
 *
 * <p>Tenant isolation and RBAC are unchanged: {@link LiveApiClient} forwards the caller's own bearer
 * token, so a tool can never return data the user could not have fetched themselves.
 *
 * <p>Descriptions are written for the model, in French, because they are what it matches the question
 * against — they are prompt material, not documentation.
 */
@Component
public class LiveToolCatalog {

    /**
     * {@code idParam} = the tool needs an entity reference; the model must supply one.
     *
     * <p>{@code triggers} are unaccented, lowercase phrases used by {@link LexicalToolSelector} when the
     * model cannot be reached. They are deliberately hand-written rather than derived from
     * {@link #description()}: the description is prose aimed at an LLM, and matching on its words alone
     * confuses tools that share vocabulary ("livraison" appears in five of them).
     */
    public record Tool(String name, String description, String uri, boolean needsId, String label,
                       List<String> triggers) {}

    private static final List<Tool> TOOLS = List.of(
            new Tool("ops_overview",
                    "Vue d'ensemble de l'activité du jour : volumes, livraisons en cours, terminées, en retard.",
                    "/api/v1/admin/ops/overview", false, "Activité du jour",
                    List.of("vue d'ensemble", "apercu", "activite du jour", "resume du jour",
                            "operations du jour", "ca donne quoi")),
            new Tool("deliveries_counts",
                    "Nombre de livraisons par statut (combien en attente, en transit, livrées, échouées).",
                    "/api/v1/admin/deliveries/counts", false, "Compteurs de livraisons",
                    List.of("combien de livraison", "combien de livraisons", "nombre de livraisons",
                            "livraisons par statut", "mes livraisons", "livraisons en cours")),
            new Tool("deliveries_stats",
                    "Statistiques agrégées des livraisons (taux de réussite, délais).",
                    "/api/v1/admin/deliveries/stats", false, "Statistiques livraisons",
                    List.of("statistiques", "taux de reussite", "taux de succes", "delais moyens",
                            "performance des livraisons")),
            new Tool("ops_exceptions",
                    "Livraisons en souffrance / anomalies opérationnelles à traiter.",
                    "/api/v1/admin/ops/exceptions", false, "Exceptions opérationnelles",
                    List.of("exception", "exceptions", "anomalie", "anomalies", "en souffrance",
                            "incident operationnel", "a traiter", "bloquee", "bloquees")),
            new Tool("depots_active",
                    "Liste des dépôts (entrepôts) actifs de l'entreprise, avec leur adresse.",
                    "/api/v1/depots/active", false, "Dépôts actifs",
                    List.of("depot", "depots", "entrepot", "entrepots")),
            new Tool("zones_active",
                    "Liste des zones de livraison actives.",
                    "/api/v1/zones/active", false, "Zones actives",
                    List.of("zone", "zones", "secteur", "secteurs")),
            new Tool("drivers_stats",
                    "État de la flotte de livreurs : combien en ligne, en pause, hors ligne.",
                    "/api/v1/admin/fleet/drivers/stats", false, "État de la flotte",
                    List.of("flotte", "combien de livreurs", "nombre de livreurs", "livreurs en ligne",
                            "livreurs hors ligne", "etat des livreurs")),
            new Tool("drivers_available",
                    "Livreurs actuellement disponibles pour recevoir une tournée.",
                    "/api/v1/admin/fleet/drivers/available", false, "Livreurs disponibles",
                    List.of("disponible", "disponibles", "disponibilite", "qui peut livrer")),
            new Tool("cash_circulation",
                    "Argent encaissé à la livraison (COD) actuellement en circulation chez les livreurs.",
                    "/api/v1/admin/cash/circulation", false, "Espèces en circulation",
                    List.of("especes", "cash", "argent en circulation", "cod en circulation",
                            "argent chez les livreurs", "combien d'argent")),
            new Tool("cash_outstanding_by_driver",
                    "Montants COD non encore remis, détaillés par livreur.",
                    "/api/v1/admin/cash/outstanding-by-driver", false, "COD dû par livreur",
                    List.of("non remis", "pas encore remis", "cod du", "du par livreur",
                            "remise de caisse", "qui doit de l'argent")),
            new Tool("returns_kpi",
                    "Indicateurs des retours (RMA) : volumes et états.",
                    "/api/v1/admin/returns/kpi", false, "KPI retours",
                    List.of("combien de retour", "combien de retours", "nombre de retours",
                            "mes retours", "kpi retours", "retours en cours", "volume de retours")),
            new Tool("system_health",
                    "Santé de la plateforme et de l'intégration ERP (dernière synchronisation, incidents).",
                    "/api/v1/admin/system/health", false, "Santé du système",
                    List.of("sante du systeme", "sante de la plateforme", "tout fonctionne",
                            "y a-t-il un incident", "systeme en panne")),
            new Tool("erp_sync_history",
                    "Historique récent des synchronisations avec l'ERP (Odoo / ERPNext).",
                    "/api/v1/admin/system/erp-sync/history", false, "Historique de synchronisation ERP",
                    List.of("derniere synchronisation", "derniere synchro", "historique de synchronisation",
                            "synchronisation erp", "sync erp", "erp a jour")),

            // ── Entity lookups: the model must supply the reference found in the question ──
            // No triggers: these are unreachable without an identifier, and IntentRouter already sends
            // "<hint> + <reference>" questions straight to the live path without consulting the model.
            new Tool("delivery_by_id",
                    "État actuel d'UNE livraison précise, identifiée par son UUID ou sa référence.",
                    "/api/v1/admin/deliveries/{id}", true, "Livraison", List.of()),
            new Tool("delivery_sla",
                    "Verdict SLA d'une livraison précise (respecté ou non) calculé par le moteur SLA.",
                    "/api/v1/admin/deliveries/{id}/sla-timeline", true, "SLA de la livraison", List.of()),
            new Tool("route_by_id",
                    "État actuel d'UNE tournée précise, identifiée par son identifiant.",
                    "/api/v1/admin/routes/{id}", true, "Tournée", List.of()),
            new Tool("route_driver_location",
                    "Position GPS actuelle du livreur affecté à une tournée précise.",
                    "/api/v1/admin/routes/{id}/driver-location", true, "Position du livreur", List.of()),
            new Tool("return_by_id",
                    "État actuel d'UN retour (RMA) précis, identifié par sa référence.",
                    "/api/v1/admin/returns/{id}", true, "Retour (RMA)", List.of())
    );

    public List<Tool> all() {
        return TOOLS;
    }

    public Optional<Tool> byName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String wanted = name.trim();
        return TOOLS.stream().filter(t -> t.name().equalsIgnoreCase(wanted)).findFirst();
    }

    /** The catalogue as the model sees it: one line per tool, name first. */
    public String asPromptCatalogue() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : TOOLS) {
            sb.append("- ").append(t.name())
              .append(t.needsId() ? " (nécessite une référence) : " : " : ")
              .append(t.description()).append('\n');
        }
        return sb.toString();
    }
}
