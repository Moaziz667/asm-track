import { i18nBuilder } from "keycloakify/login/i18n";

/**
 * Brand copy for the sign-in shell, in the three locales the product ships.
 *
 * Keycloak's own message bundle covers the form — labels, errors, the "forgot password" flow.
 * What it has no vocabulary for is what ASM Track actually is, which is the whole point of the
 * panel beside the form: someone arriving at this page from an email link needs to know which
 * system is asking for their password.
 */
const { useI18n, ofTypeI18n } = i18nBuilder
  .withCustomTranslations({
    fr: {
      asmTagline: "La plateforme de pilotage des livraisons",
      asmIntro:
        "Importez les commandes depuis votre ERP, planifiez les tournées, suivez les chauffeurs en direct et renvoyez les résultats — preuves de livraison comprises — sans double saisie.",
      asmPointPlan: "Planification des tournées",
      asmPointPlanSub: "Regroupez par zone et par créneau, assignez un chauffeur, validez la tournée.",
      asmPointTrack: "Suivi en direct",
      asmPointTrackSub: "Position des chauffeurs, retards SLA et incidents au moment où ils surviennent.",
      asmPointProof: "Preuve de livraison",
      asmPointProofSub: "Signature, photos et motif d'échec remontés depuis le terrain, même hors ligne.",
      asmSecure: "Connexion chiffrée",
    },
    en: {
      asmTagline: "The delivery operations platform",
      asmIntro:
        "Pull orders from your ERP, plan the routes, follow drivers live, and send the outcomes back — proofs of delivery included — without keying anything twice.",
      asmPointPlan: "Route planning",
      asmPointPlanSub: "Group by zone and time slot, assign a driver, commit the round.",
      asmPointTrack: "Live tracking",
      asmPointTrackSub: "Driver positions, SLA breaches and incidents as they happen.",
      asmPointProof: "Proof of delivery",
      asmPointProofSub: "Signature, photos and failure reason captured in the field, offline included.",
      asmSecure: "Encrypted connection",
    },
    ar: {
      asmTagline: "منصة إدارة عمليات التوصيل",
      asmIntro:
        "استورد الطلبات من نظامك، خطّط الجولات، تابع السائقين مباشرة، وأعد النتائج — بما فيها إثباتات التسليم — دون إدخال مزدوج.",
      asmPointPlan: "تخطيط الجولات",
      asmPointPlanSub: "التجميع حسب المنطقة والفترة، تعيين سائق، ثم اعتماد الجولة.",
      asmPointTrack: "تتبع مباشر",
      asmPointTrackSub: "مواقع السائقين، تجاوزات اتفاقية الخدمة والحوادث لحظة وقوعها.",
      asmPointProof: "إثبات التسليم",
      asmPointProofSub: "التوقيع والصور وسبب الفشل من الميدان، حتى دون اتصال.",
      asmSecure: "اتصال مشفَّر",
    },
  })
  .build();

export { useI18n, ofTypeI18n };
export type I18n = typeof ofTypeI18n;
