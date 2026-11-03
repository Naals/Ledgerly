package com.project.ledgercore.hold;

import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.Money;
import java.time.Duration;

/** The rules about a hold's lifetime and capture amount, kept free of I/O so they are easy to test. */
public final class HoldRules {

    public static final Duration MIN_TTL = Duration.ofMinutes(1);
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);
    public static final Duration MAX_TTL = Duration.ofDays(7);

    private HoldRules() {
    }

    /** How long a hold lasts: the default when the client names none, otherwise one minute to seven days. */
    public static Duration resolveTtl(Long requestedSeconds) {
        if (requestedSeconds == null) {
            return DEFAULT_TTL;
        }
        Duration ttl = Duration.ofSeconds(requestedSeconds);
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
            throw new BadRequestException("invalid-ttl",
                    "ttlSeconds must be between " + MIN_TTL.toSeconds() + " and " + MAX_TTL.toSeconds());
        }
        return ttl;
    }

    /**
     * What a capture takes: the whole hold when no amount is named, otherwise a positive amount that is
     * at most the hold. Whatever is not captured becomes available again.
     */
    public static Money resolveCaptureAmount(Money held, Long requestedMinor) {
        if (requestedMinor == null) {
            return held;
        }
        if (requestedMinor <= 0) {
            throw new BadRequestException("invalid-amount", "The captured amount must be positive");
        }
        Money requested = new Money(requestedMinor, held.currency());
        if (requested.minorUnits() > held.minorUnits()) {
            throw new CaptureExceedsHoldException(held, requested);
        }
        return requested;
    }
}