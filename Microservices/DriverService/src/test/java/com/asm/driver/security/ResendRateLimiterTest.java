package com.asm.driver.security;

import com.asm.driver.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cooldown on "resend my invite".
 *
 * <p>The endpoint is public and sends an SMS, so without a limit a stranger with a phone number can
 * bill the company for as many messages as they care to request.
 */
class ResendRateLimiterTest {

    @Test
    void allowsTheFirstRequestForAPhone() {
        var limiter = new ResendRateLimiter(60);
        assertThatCode(() -> limiter.check("+21620000001")).doesNotThrowAnyException();
    }

    @Test
    void rejectsASecondRequestInsideTheCooldown() {
        var limiter = new ResendRateLimiter(60);
        limiter.check("+21620000002");
        assertThatThrownBy(() -> limiter.check("+21620000002"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("RESEND_RATE_LIMITED");
    }

    @Test
    void tellsTheCallerHowLongToWait() {
        var limiter = new ResendRateLimiter(60);
        limiter.check("+21620000003");
        assertThatThrownBy(() -> limiter.check("+21620000003"))
                .isInstanceOfSatisfying(AppException.class,
                        // The app shows a countdown; a zero or negative hint would render as an
                        // invitation to hammer the button.
                        e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    void limitsEachPhoneSeparately() {
        // One driver asking twice must not lock out the rest of the fleet.
        var limiter = new ResendRateLimiter(60);
        limiter.check("+21620000004");
        assertThatCode(() -> limiter.check("+21620000005")).doesNotThrowAnyException();
    }

    @Test
    void allowsAgainOnceTheWindowHasPassed() {
        // A zero-second cooldown is the same code path with the window already elapsed, which lets
        // this assert the "allowed again" branch without making the suite sleep for a minute.
        var limiter = new ResendRateLimiter(0);
        limiter.check("+21620000006");
        assertThatCode(() -> limiter.check("+21620000006")).doesNotThrowAnyException();
    }

    @Test
    void forgetsAPhoneThatIsExplicitlyReset() {
        var limiter = new ResendRateLimiter(60);
        limiter.check("+21620000007");
        limiter.reset("+21620000007");
        assertThatCode(() -> limiter.check("+21620000007")).doesNotThrowAnyException();
    }
}
