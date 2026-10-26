package com.example.coupon_issue_system.kafka;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CouponIssueProducer {

    public static final String TOPIC = "coupon-issue";

    private final KafkaTemplate<String, String> kafkaTemplate;

    /*
     * Redis 에서 당첨 판정이 끝난 요청을 토픽으로 발행한다.
     * key = couponId : 같은 쿠폰의 메시지는 같은 파티션에 들어가 순서가 보장된다.
     * */
    public void send(Long couponId, Long userId) {
        kafkaTemplate.send(TOPIC, String.valueOf(couponId), couponId + ":" + userId);
    }
}
