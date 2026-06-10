# Sync Odoo → ASM : la règle à créer dans Odoo (webhook)

Ce document explique, en langage simple, **la seule chose à configurer côté Odoo** pour que les
changements faits *dans* Odoo (annulation, modification d'une commande) remontent en temps réel vers
ASM Track. Sans cette règle, ça marche quand même — mais seulement toutes les ~10 minutes (le filet de
sécurité par sondage). La règle ci-dessous rend la remontée **instantanée**.

> Tout le code ASM est déjà en place. Il n'y a **rien à programmer** : juste une règle d'automatisation
> Odoo (module *Automation* / *Studio*, standard).

---

## Ce qu'on veut

Quand une **commande client (`sale.order`)** est **annulée** ou **modifiée** dans Odoo, Odoo doit
appeler ASM (via l'adaptateur) à cette adresse :

```
POST  http://erp-adapter:8088/api/erp/inbound/order-changed
En-tête :  X-Webhook-Secret: <le secret configuré (erp.webhook.secret)>
Corps (JSON) :
{
  "erpOrderId":   "S00123",         // la référence de la commande (champ name)
  "changeType":   "CANCELLED",      // CANCELLED | LINES | ADDRESS | DATE
  "odooWriteDate":"2026-06-11 09:32:10",   // le write_date de la commande
  "payload": { ... }                // optionnel selon le type (voir plus bas)
}
```

ASM applique ensuite sa règle : **avant départ de la livraison → on applique ; après départ → on
ignore et on alerte un humain.**

---

## Comment créer la règle dans Odoo

1. Active le mode développeur (Réglages → Activer le mode développeur).
2. Va dans **Réglages → Technique → Automatisations → Règles d'automatisation**.
3. **Nouvelle règle** :
   - **Modèle** : `Commande client` (`sale.order`).
   - **Déclencheur** : *À la mise à jour* (et/ou *À la création* si besoin).
   - (Optionnel) **Domaine** : limiter aux commandes confirmées si tu veux.
4. **Action** : *Exécuter du code Python* (ou un webhook si ta version le propose nativement).

### Exemple d'action Python (envoi du webhook)

```python
import requests

ASM_URL = "http://erp-adapter:8088/api/erp/inbound/order-changed"
SECRET  = "REMPLACE_PAR_TON_SECRET"   # = erp.webhook.secret côté adaptateur

for order in records:
    # Type de changement : annulation prioritaire, sinon "DATE" (snapshot date promise)
    if order.state == "cancel":
        change_type = "CANCELLED"
        payload = {}
    else:
        change_type = "DATE"
        payload = {"scheduledAt": str(order.commitment_date or "")}

    body = {
        "erpOrderId":    order.name,
        "changeType":    change_type,
        "odooWriteDate": str(order.write_date),
        "payload":       payload,
    }
    try:
        requests.post(ASM_URL, json=body,
                      headers={"X-Webhook-Secret": SECRET}, timeout=5)
    except Exception:
        # Best-effort : si l'appel échoue, le sondage ASM rattrapera le changement.
        pass
```

> Pour aussi remonter les **changements de lignes/quantités**, ajoute un `changeType="LINES"` avec
> `payload={"items":[{"sku":..., "quantity":...}, ...]}` construit depuis `order.order_line`. Le
> snapshot complet suffit : ASM remplace les lignes (avant départ uniquement).

---

## Le secret

- Côté adaptateur : définir la propriété `erp.webhook.secret` (variable d'env `ERP_WEBHOOK_SECRET`).
- Côté Odoo : mettre **la même valeur** dans l'en-tête `X-Webhook-Secret`.
- Si le secret n'est pas configuré côté adaptateur, l'endpoint accepte sans vérifier (à éviter en prod).

---

## Et si le webhook tombe ?

Aucune inquiétude : l'adaptateur **sonde Odoo toutes les ~10 minutes** (`ErpChangePoller`) pour les
commandes modifiées depuis la dernière fois, et envoie les mêmes changements. Donc même si une
notification se perd (Odoo redémarre, réseau coupé), **ASM finit toujours par voir le changement**. Le
webhook ne fait qu'accélérer ; le sondage garantit.

Réglages du sondage (côté adaptateur) :
- `erp.inbound.poll.enabled` (défaut `true`)
- `erp.inbound.poll.fixed-delay-ms` (défaut `600000` = 10 min)
