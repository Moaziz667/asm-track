package com.asm.delivery.dto.canonical;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Java counterpart of the @asm/canonical-model TypeScript interface.
 * Consumed from RabbitMQ queue orders.created (published by Mapper Service).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CanonicalDelivery {
    private Identity   identity;
    private String     status;   // DRAFT | READY | IN_TRANSIT | DELIVERED | FAILED | CANCELLED
    private Planning   planning;
    private Origin     origin;
    private Destination destination;
    private Load       load;
    private Financial  financial;
    private Metadata   metadata;

    @Data @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Identity {
        private String id;
        private String externalReference;
        private String sourceSystem;
        private String createdAt;
        private String updatedAt;
        private String schemaVersion;
    }

    @Data @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Planning {
        private String scheduledAt;
        private String priority;  // NORMAL | HIGH
    }

    @Data @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Address {
        private String fullAddress;
        private String city;
        private String postalCode;
        private String countryCode;
    }

    @Data @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Contact {
        private String name;
        private String phone;
        private String email;
    }

    @Data @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Origin {
        private String  name;
        private Address address;
        private Contact contact;
    }

    @Data @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Destination {
        private String  name;
        private Address address;
        private Contact contact;
        private String  deliveryInstructions;
    }

    @Data @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoadItem {
        private String     id;
        private String     sku;
        private String     name;
        private Integer    quantity;
        private Integer    quantityDone;
        private BigDecimal unitWeightKg;
    }

    @Data @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Load {
        private Integer        totalQuantity;
        private BigDecimal     totalWeightKg;
        private List<LoadItem> items;
    }

    @Data @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Financial {
        private BigDecimal totalAmount;
        private String     currency;
    }

    @Data @NoArgsConstructor @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        private String externalId;    // Odoo integer ID as string
        private String lastSyncedAt;
    }
}
