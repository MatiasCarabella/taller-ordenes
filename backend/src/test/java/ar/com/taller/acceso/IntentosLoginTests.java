package ar.com.taller.acceso;

import java.time.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntentosLoginTests {
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-07T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test void locksAccountAcrossOriginsAndUnlocksAfterFifteenMinutes() {
        var clock = new TestClock();
        var limiter = new IntentosLogin(clock);
        for (int i=0;i<5;i++) try(var permit=limiter.begin("cuenta","origen"+i)) { permit.failed(); }
        assertThrows(IntentosLogin.LimitExceeded.class, () -> limiter.begin("cuenta","otro"));
        clock.now = clock.now.plus(Duration.ofMinutes(15));
        try(var permit=limiter.begin("cuenta","otro")) { permit.succeeded(); }
    }

    @Test void locksOriginAcrossAccountsAndSuccessDoesNotEraseOtherFailures() {
        var limiter = new IntentosLogin(new TestClock());
        for (int i=0;i<4;i++) try(var permit=limiter.begin("cuenta"+i,"origen")) { permit.failed(); }
        try(var permit=limiter.begin("correcta","origen")) { permit.succeeded(); }
        try(var permit=limiter.begin("quinta","origen")) { permit.failed(); }
        assertThrows(IntentosLogin.LimitExceeded.class, () -> limiter.begin("sexta","origen"));
    }

    @Test void boundsConcurrentAttemptsAndReleasesUnfinishedPermits() {
        var limiter = new IntentosLogin(new TestClock());
        var permits = new ArrayList<IntentosLogin.Permit>();
        for(int i=0;i<5;i++) permits.add(limiter.begin("cuenta","origen"));
        assertThrows(IntentosLogin.LimitExceeded.class, () -> limiter.begin("cuenta","origen"));
        permits.forEach(IntentosLogin.Permit::close);
        try(var permit=limiter.begin("cuenta","origen")) { permit.succeeded(); }
    }
}
