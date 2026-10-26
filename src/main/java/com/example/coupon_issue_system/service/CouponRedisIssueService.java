package com.example.coupon_issue_system.service;

import com.example.coupon_issue_system.entity.Coupon;
import com.example.coupon_issue_system.kafka.CouponIssueProducer;
import com.example.coupon_issue_system.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/*
 * 3단계: Redis + Kafka 버전
 * - 중복/수량 판정: Redis Lua 스크립트 (단일 스레드 원자 실행 → DB 락 불필요)
 * - DB 반영: Kafka 로 발행 후 컨슈머가 비동기 처리 (쓰기 부하 평탄화)
 * 요청 스레드는 MySQL 을 전혀 거치지 않는다.
 *
 * [Redis 데이터 구조] — prepareEvent() 시점에 적재, TTL 없음(이벤트 종료 후 별도 정리 필요)
 *  coupon:{couponId}:total         String  총 발급 가능 수량            예) "100"
 *  coupon:{couponId}:count         String  현재 발급 수 (INCR 로 증가)  예) "100"
 *  coupon:{couponId}:issued-users  Set     발급받은 userId 집합         예) {"1","42","999"}
 * */
@Service
@RequiredArgsConstructor
public class CouponRedisIssueService {

    private final StringRedisTemplate redisTemplate;
    private final CouponIssueProducer couponIssueProducer;
    private final CouponRepository couponRepository;

    /*
     * KEYS[1] = 발급 유저 Set, KEYS[2] = 총 수량, KEYS[3] = 발급 카운터
     * ARGV[1] = userId
     * 반환: 1 당첨 / 0 매진 / -1 중복 / -2 이벤트 미등록
     * */
    private static final RedisScript<Long> ISSUE_SCRIPT = RedisScript.of("""
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
                return -1
            end
            local total = tonumber(redis.call('GET', KEYS[2]))
            if total == nil then
                return -2
            end
            local count = tonumber(redis.call('GET', KEYS[3]) or '0')
            if count >= total then
                return 0
            end
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('INCR', KEYS[3])
            return 1
            """, Long.class);

    /*
     * 이벤트 시작 전 쿠폰 정보를 Redis 에 적재한다.
     * 요청 처리 중에는 MySQL 조회가 발생하지 않도록 하기 위함.
     * */
    public void prepareEvent(Long couponId) {

        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 쿠폰입니다."));

        redisTemplate.opsForValue().set(totalKey(couponId), String.valueOf(coupon.getTotalQuantity()));
        redisTemplate.opsForValue().set(countKey(couponId), String.valueOf(coupon.getIssuedQuantity()));
        redisTemplate.delete(issuedUsersKey(couponId));
    }

    public void issue(Long couponId, Long userId) {

        Long result = redisTemplate.execute(ISSUE_SCRIPT,
                List.of(issuedUsersKey(couponId), totalKey(couponId), countKey(couponId)),
                String.valueOf(userId));

        if (result == -1) {
            throw new RuntimeException("이미 발급된 쿠폰입니다.");
        }
        if (result == -2) {
            throw new IllegalArgumentException("이벤트가 등록되지 않은 쿠폰입니다.");
        }
        if (result == 0) {
            throw new RuntimeException("선착순 수량이 마감되었습니다.");
        }

        //당첨 확정 → DB 반영은 Kafka 컨슈머에게 위임
        couponIssueProducer.send(couponId, userId);
    }

    private String issuedUsersKey(Long couponId) {
        return "coupon:" + couponId + ":issued-users";
    }

    private String totalKey(Long couponId) {
        return "coupon:" + couponId + ":total";
    }

    private String countKey(Long couponId) {
        return "coupon:" + couponId + ":count";
    }
}
