package com.example.coupon_issue_system.service;

import com.example.coupon_issue_system.entity.Coupon;
import com.example.coupon_issue_system.repository.CouponRepository;
import com.example.coupon_issue_system.repository.IssuedCouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class CouponIssueServiceTest {

    @Autowired private CouponIssueService couponIssueService;
    @Autowired private CouponRepository couponRepository;
    @Autowired private IssuedCouponRepository issuedCouponRepository;

    @BeforeEach
    void setUp() {
        // 테스트 간 독립성 보장
        issuedCouponRepository.deleteAllInBatch();
        couponRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("100개 한정 쿠폰 발행 테스트(동시 요청 1000개)")
    void concurrencyTest() throws InterruptedException {

        // 1. 준비
        Coupon coupon = Coupon.builder()
                .name("선착순 100명 이벤트")
                .totalQuantity(100)
                .build();

        couponRepository.save(coupon);

        int threadCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // 2. 실행
        for (int i = 0; i < threadCount; i++) {
            long userId = i + 1;
            executorService.execute(() -> {
                try {
                    couponIssueService.issue(coupon.getId(), userId);
                } catch (Exception e) {
                    // 마감/중복 등 발급 실패는 정상 거절로 간주
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // 3. 결과 조회
        long issuedCount = issuedCouponRepository.countByCouponId(coupon.getId());
        Coupon updatedCoupon = couponRepository.findById(coupon.getId()).orElseThrow();

        System.out.printf("[비관적 락] 발급행 %d / 카운터 %d%n", issuedCount, updatedCoupon.getIssuedQuantity());

        // 4. 검증
        assertThat(issuedCount).isEqualTo(100);
        assertThat(updatedCoupon.getIssuedQuantity()).isEqualTo(100);
    }
}