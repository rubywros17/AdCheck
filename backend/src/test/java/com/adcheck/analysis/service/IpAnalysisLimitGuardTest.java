package com.adcheck.analysis.service;

import com.adcheck.analysis.config.AnalysisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IpAnalysisLimitGuardTest {

    private static final String IP_A = "203.0.113.10";
    private static final String IP_B = "203.0.113.20";

    private AnalysisProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AnalysisProperties();
        properties.setIpDailyLimit(10);
    }

    /** 테스트 안에서 "지금"을 자유롭게 옮길 수 있는 UTC 기준 Clock. */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant) {
            this(instant, ZoneOffset.UTC);
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private IpAnalysisLimitGuard guardAt(Clock clock) {
        return new IpAnalysisLimitGuard(properties, clock);
    }

    @Test
    void allowsUpToLimitThenRejectsTheNextAttempt() {
        IpAnalysisLimitGuard guard = guardAt(Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneOffset.UTC));

        for (int i = 0; i < 10; i++) {
            guard.acquire(IP_A);
        }

        assertThatThrownBy(() -> guard.acquire(IP_A))
                .isInstanceOfSatisfying(IpAnalysisLimitExceededException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.getCode()).isEqualTo("IP_ANALYSIS_LIMIT_EXCEEDED");
                });
    }

    @Test
    void rejectedAttemptsDoNotConsumeQuotaAndOtherIpsAreUnaffected() {
        IpAnalysisLimitGuard guard = guardAt(Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneOffset.UTC));

        for (int i = 0; i < 10; i++) {
            guard.acquire(IP_A);
        }

        assertThatThrownBy(() -> guard.acquire(IP_A)).isInstanceOf(IpAnalysisLimitExceededException.class);
        assertThatThrownBy(() -> guard.acquire(IP_A)).isInstanceOf(IpAnalysisLimitExceededException.class);

        assertThatCode(() -> guard.acquire(IP_B)).doesNotThrowAnyException();
    }

    @Test
    void tracksEachIpIndependently() {
        IpAnalysisLimitGuard guard = guardAt(Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneOffset.UTC));

        for (int i = 0; i < 10; i++) {
            guard.acquire(IP_A);
        }

        assertThatThrownBy(() -> guard.acquire(IP_A)).isInstanceOf(IpAnalysisLimitExceededException.class);
        assertThatCode(() -> guard.acquire(IP_B)).doesNotThrowAnyException();
    }

    @Test
    void treatsNullIpAsUnknownAndStillLimits() {
        IpAnalysisLimitGuard guard = guardAt(Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneOffset.UTC));

        for (int i = 0; i < 10; i++) {
            guard.acquire(null);
        }

        assertThatThrownBy(() -> guard.acquire(null)).isInstanceOf(IpAnalysisLimitExceededException.class);
    }

    @Test
    void resetsWhenKstDateChanges() {
        // KST 10/8 23:59에 10건 채움
        MutableClock clock = new MutableClock(Instant.parse("2026-10-08T14:59:00Z"));
        IpAnalysisLimitGuard guard = guardAt(clock);

        for (int i = 0; i < 10; i++) {
            guard.acquire(IP_A);
        }
        assertThatThrownBy(() -> guard.acquire(IP_A)).isInstanceOf(IpAnalysisLimitExceededException.class);

        // KST 10/9 00:01로 넘어가면(= UTC 10/8 15:01) 같은 IP도 다시 허용돼야 함
        clock.set(Instant.parse("2026-10-08T15:01:00Z"));

        assertThatCode(() -> guard.acquire(IP_A)).doesNotThrowAnyException();
    }

    @Test
    void allowsExactlyLimitConcurrentAcquiresForSameIp() throws InterruptedException {
        IpAnalysisLimitGuard guard = guardAt(Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneOffset.UTC));
        int threadCount = 50;
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finishLine = new CountDownLatch(threadCount);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        guard.acquire(IP_A);
                        succeeded.incrementAndGet();
                    } catch (IpAnalysisLimitExceededException expected) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishLine.countDown();
                    }
                });
            }

            startLine.countDown();
            boolean finished = finishLine.await(10, TimeUnit.SECONDS);

            assertThat(finished).as("모든 스레드가 제한 시간 안에 끝나야 함").isTrue();
            assertThat(succeeded.get()).isEqualTo(10);
            assertThat(rejected.get()).isEqualTo(40);
        } finally {
            pool.shutdownNow();
        }
    }
}
