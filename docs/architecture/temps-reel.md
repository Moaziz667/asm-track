# Temps réel et notifications

## Pourquoi RabbitMQ relaie le WebSocket

```mermaid
flowchart TB
    subgraph Sans["❌ Courtier en mémoire"]
        I1["Instance 1<br/>+ abonnés A, B"]
        I2["Instance 2<br/>+ abonnés C, D"]
        E1["événement reçu<br/>par l'instance 1"] --> I1
        I1 -.->|"C et D ne voient rien"| I2
    end

    subgraph Avec["✅ Relais STOMP RabbitMQ"]
        J1["Instance 1"] --> MQ{{"RabbitMQ<br/>:61613"}}
        J2["Instance 2"] --> MQ
        MQ --> T["/topic/…"]
        T --> K["tous les abonnés,<br/>quelle que soit l'instance"]
    end

    style Sans fill:#ffebee,stroke:#c62828
    style Avec fill:#e8f5e9,stroke:#2e7d32
```

```java
registry.enableStompBrokerRelay("/topic").setRelayHost(rabbitHost);
registry.setApplicationDestinationPrefixes("/app");
registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
```

**Le courtier en mémoire de Spring fonctionne tant qu'il n'y a qu'une instance.** Dès qu'on en
déploie deux, un dispatcher connecté à la seconde ne voit plus les événements produits par la
première. Le relais RabbitMQ élimine cette dépendance à la topologie.

!!! note "Ce que ça n'apporte pas"
    Ce n'est pas de la persistance. Un client déconnecté au moment de l'émission **rate**
    l'événement. Les données restent en base ; le WebSocket ne sert qu'à éviter d'attendre le
    prochain rafraîchissement.

---

## Qui écoute quoi

```mermaid
flowchart LR
    subgraph Producteurs
        D["DeliveryMicroservice<br/>EventPublisher"]
    end
    subgraph Destinations
        T1["/topic/deliveries"]
        T2["/topic/routes"]
        T3["/topic/drivers"]
        T4["/topic/public.{deliveryId}"]
        T5["/topic/notifications"]
    end
    subgraph Abonnés
        W["Back-office<br/>(dispatch, tableau de bord)"]
        P["Page de suivi public"]
    end

    D --> T1 & T2 & T3 & T4 & T5
    T1 & T2 & T3 & T5 --> W
    T4 --> P

    style T4 fill:#fff3e0,stroke:#ef6c00
```

**`/topic/public.{deliveryId}` est isolé volontairement.** La page de suivi n'est pas authentifiée :
elle ne doit recevoir **que** les événements de la livraison dont elle porte l'identifiant, jamais un
flux global.

---

## La sécurité du WebSocket

Un intercepteur (`WebSocketSecurityInterceptor`) valide le jeton **à la connexion STOMP**, pas
seulement à l'ouverture HTTP.

!!! danger "Erreur classique"
    Croire que `setAllowedOriginPatterns("*")` ouvre une faille. L'origine n'est pas un contrôle
    d'accès : le contrôle réel est le jeton présenté dans la trame `CONNECT`. Une origine permissive
    est nécessaire ici parce que le back-office et le suivi public sont servis depuis des origines
    différentes.

---

## Les notifications persistantes

Deux mécanismes coexistent et ne servent pas la même chose :

```mermaid
flowchart TD
    E["Événement métier<br/>(livraison échouée, SLA dépassé…)"]
    E --> W["WebSocket<br/>→ apparaît immédiatement"]
    E --> N["Table notifications<br/>→ survit au rechargement"]
    N --> B["Cloche du back-office<br/>lecture / non-lecture"]

    style W fill:#e3f2fd,stroke:#1565c0
    style N fill:#e8f5e9,stroke:#2e7d32
```

| | WebSocket | Table `notifications` |
|---|---|---|
| Portée | l'instant | l'historique |
| Si le dispatcher est absent | perdu | conservé |
| Usage | badge qui apparaît en direct | liste consultable, marquage lu |

!!! tip "Pourquoi les deux"
    Un dispatcher qui arrive à 9 h doit voir ce qui s'est passé à 7 h. Le WebSocket seul ne le
    permet pas ; la table seule imposerait un rafraîchissement permanent.

---

## Notifications mobiles (FCM)

L'application livreur enregistre un jeton FCM (`PUT /driver/fcm-token`), effacé à la déconnexion. Les
notifications poussées servent les événements que le livreur doit voir **application fermée** : une
nouvelle livraison assignée, un transfert de garde à confirmer.
