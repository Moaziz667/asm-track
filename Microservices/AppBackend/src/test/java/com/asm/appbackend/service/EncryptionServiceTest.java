package com.asm.appbackend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The encryption that protects tenants' ERP credentials at rest.
 *
 * <p>These are live credentials to a customer's own ERP, so what matters is not only that a
 * round-trip works — a broken cipher would be noticed immediately — but that the properties the
 * design relies on actually hold: a fresh IV per call, and a tag that refuses tampered input.
 * Both are silent when wrong.
 */
class EncryptionServiceTest {

    private final EncryptionService service = new EncryptionService("a-test-key-that-is-not-the-real-one");

    @Test
    void returnsWhatWasEncrypted() {
        String secret = "odoo-api-key-9f2b";
        assertThat(service.decrypt(service.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void survivesAccentsAndSymbols() {
        // ERP passwords are entered by hand by customers, not generated.
        String secret = "mot-de-passe-é&#~çà 中文";
        assertThat(service.decrypt(service.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void producesADifferentCiphertextEveryTime() {
        // GCM must never reuse an IV with the same key. If two encryptions of the same value came
        // out identical, the IV would be fixed — which leaks that two tenants share a password and,
        // worse, breaks the cipher's guarantees outright.
        String secret = "same-input";
        assertThat(service.encrypt(secret)).isNotEqualTo(service.encrypt(secret));
    }

    @Test
    void refusesCiphertextThatHasBeenTamperedWith() {
        // The authentication tag is the point of GCM over plain AES: a modified value must fail
        // loudly rather than decrypt to plausible-looking garbage that gets sent to an ERP.
        String encrypted = service.encrypt("odoo-api-key-9f2b");
        char[] chars = encrypted.toCharArray();
        chars[chars.length - 2] = chars[chars.length - 2] == 'A' ? 'B' : 'A';

        assertThatThrownBy(() -> service.decrypt(new String(chars)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Decryption failed");
    }

    @Test
    void refusesAValueEncryptedUnderAnotherKey() {
        // What a restore into the wrong environment looks like: the database is fine, the key is
        // not. Failing is correct; returning nonsense would push nonsense to the customer's ERP.
        String fromElsewhere = new EncryptionService("a-different-key").encrypt("odoo-api-key-9f2b");
        assertThatThrownBy(() -> service.decrypt(fromElsewhere))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void refusesInputTooShortToContainAnything() {
        assertThatThrownBy(() -> service.decrypt("dG9vLXNob3J0"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void treatsAbsentValuesAsAbsentRatherThanFailing() {
        // A tenant that has not configured an ERP has no credentials — that is a normal state, not
        // an error, and it must not blow up the settings page.
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.encrypt("")).isNull();
        assertThat(service.encrypt("   ")).isNull();
        assertThat(service.decrypt(null)).isNull();
        assertThat(service.decrypt("")).isNull();
    }

    @Test
    void acceptsAnyKeyLengthFromConfiguration() {
        // The key comes from an environment variable a human typed. It is hashed to 256 bits, so a
        // short one must still work rather than throwing at startup.
        var shortKey = new EncryptionService("x");
        assertThat(shortKey.decrypt(shortKey.encrypt("value"))).isEqualTo("value");
    }
}
