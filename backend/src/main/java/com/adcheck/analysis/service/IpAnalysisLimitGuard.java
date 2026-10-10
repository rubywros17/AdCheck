package com.adcheck.analysis.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.adcheck.analysis.config.AnalysisProperties;

/**
 * IP별 하루 새 분석 수를 메모리에서 제한한다.
 * 서버 재시작 시 초기화되는 보조 제한이며, 최종 상한은 DailyAnalysisLimitGuard(DB)가 지킨다.
 */
@Component 
public class IpAnalysisLimitGuard {
    
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AnalysisProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Key, Integer> counts = new ConcurrentHashMap<>();
    private volatile LocalDate lastSeenDate;

    public IpAnalysisLimitGuard(AnalysisProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 이 IP의 오늘 사용 횟수를 1 늘린다. 이미 한도에 도달했으면 늘리지 않고 예외를 던진다. */
    public void acquire(String clientIp) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        evictPastDays(today);

        int limit = properties.getIpDailyLimit();
        Key key = new Key(today, clientIp == null ? "unknown" : clientIp);

        counts.compute(key, (k, used) -> {
            int current = used == null ? 0 : used;
            if (current >= limit) {
                throw new IpAnalysisLimitExceededException(limit);
            }
            return current + 1;
        });
    }

    /** 날짜가 바뀌면 지난 날짜의 카운트를 지워 메모리가 쌓이지 않게 한다. */
    private void evictPastDays(LocalDate today) {
        if (!today.equals(lastSeenDate)) {
            lastSeenDate = today;
            counts.keySet().removeIf(key -> !key.date().equals(today));
        }
    }

    private record Key(LocalDate date, String ip) {
    }
}
