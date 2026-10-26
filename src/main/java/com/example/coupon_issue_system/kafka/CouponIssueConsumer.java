package com.example.coupon_issue_system.kafka;

import com.example.coupon_issue_system.entity.Coupon;
import com.example.coupon_issue_system.entity.IssuedCoupon;
import com.example.coupon_issue_system.repository.CouponRepository;
import com.example.coupon_issue_system.repository.IssuedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class CouponIssueConsumer {

    private final CouponRepository couponRepository;
    private final IssuedCouponRepository issuedCouponRepository;

    /*
     * 당첨 메시지를 순차 소비하며 MySQL 에 반영
     * - 수량 판정은 이미 Redis 에서 끝났으므로 여기서는 락이 필요 없다.
     * - 카운터는 원자적 UPDATE(SET n = n + 1)로 증가시켜 컨슈머가 늘어나도 안전하다.
     * - Kafka 는 at-least-once 전달이므로 중복 소비에 대비해 멱등 처리한다.
     * */
    @KafkaListener(topics = CouponIssueProducer.TOPIC, groupId = "coupon-issue-group")
    @Transactional
    public void consume(String message) {

        String[] parts = message.split(":");
        Long couponId = Long.parseLong(parts[0]);
        Long userId = Long.parseLong(parts[1]);

        //중복 소비 멱등 처리
        if (issuedCouponRepository.existsByCouponIdAndUserId(couponId, userId)) {
            return;
        }

        //발급 수량 증가 (원자적 UPDATE)
        couponRepository.increaseIssuedQuantity(couponId);

        //IssuedCoupon 저장 (getReferenceById: SELECT 없이 FK 참조만 획득)
        Coupon coupon = couponRepository.getReferenceById(couponId);
        issuedCouponRepository.save(IssuedCoupon.builder()
                .coupon(coupon)
                .userId(userId)
                .build());
    }
}
