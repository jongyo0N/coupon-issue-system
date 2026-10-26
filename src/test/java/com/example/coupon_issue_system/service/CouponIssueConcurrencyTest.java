package com.example.coupon_issue_system.service;

import com.example.coupon_issue_system.entity.Coupon;
import com.example.coupon_issue_system.entity.enums.CouponType;
import com.example.coupon_issue_system.repository.CouponRepository;
import com.example.coupon_issue_system.repository.IssuedCouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 선착순 쿠폰 발급 동시성 테스트 (실제 MySQL 필요: docker compose up -d)
 *
 * 시나리오: 총 수량 100장 쿠폰에 서로 다른 유저 1000명이 동시에 발급 요청
 * - 락 미적용: 일반 SELECT 는 행을 잠그지 않으므로 lost update / 초과 발급 발생
 * - 비관적 락: SELECT ... FOR UPDATE 로 조회 시점부터 행을 잠가 정확히 100장만 발급
 * */
@SpringBootTest
class CouponIssueConcurrencyTest {

    private static final int TOTAL_QUANTITY = 100;  // 선착순 수량
    private static final int THREAD_COUNT = 10000;   // 동시 요청 수
    private static final int POOL_SIZE = 32;        // 동시 실행 스레드 수

    @Autowired
    private CouponIssueService couponIssueService;

    @Autowired
    private CouponRedisIssueService couponRedisIssueService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private IssuedCouponRepository issuedCouponRepository;

    private Long couponId;

    @BeforeEach
    void setUp() {
        issuedCouponRepository.deleteAllInBatch();
        couponRepository.deleteAllInBatch();

        Coupon coupon = Coupon.builder()
                .name("선착순 100장 쿠폰")
                .couponType(CouponType.FIXED_AMOUNT)
                .totalQuantity(TOTAL_QUANTITY)
                .startDate(LocalDateTime.now().minusDays(1))
                .endDate(LocalDateTime.now().plusDays(1))
                .build();

        couponId = couponRepository.save(coupon).getId();
    }

    @Test
    @DisplayName("락 미적용 (lost update / 초과 발급)")
    void issueWithoutLock_concurrency() throws InterruptedException {

        IssueResult result = runConcurrently((couponId, userId) ->
                couponIssueService.issueWithoutLock(couponId, userId));

        int counter = couponRepository.findById(couponId).orElseThrow().getIssuedQuantity();
        long issuedRows = issuedCouponRepository.count();

        printResult("락 미적용", result, counter, issuedRows);

        // 정상이라면 [성공 요청 수 == 발급된 쿠폰 행 수 == 카운터 == 100] 이어야 한다.
        // 락이 없으면 이 불변식 중 최소 하나가 깨진다.
        boolean consistent = counter == TOTAL_QUANTITY
                && issuedRows == TOTAL_QUANTITY
                && counter == issuedRows;

        assertThat(consistent)
                .as("락 없이 1000건 동시 요청 시 수량 정합성이 깨져야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("비관적 락 적용")
    void issueWithPessimisticLock_concurrency() throws InterruptedException {

        IssueResult result = runConcurrently((couponId, userId) ->
                couponIssueService.issue(couponId, userId));

        int counter = couponRepository.findById(couponId).orElseThrow().getIssuedQuantity();
        long issuedRows = issuedCouponRepository.count();

        printResult("비관적 락", result, counter, issuedRows);

        assertThat(result.successCount.get()).isEqualTo(TOTAL_QUANTITY);
        assertThat(result.failCount.get()).isEqualTo(THREAD_COUNT - TOTAL_QUANTITY);
        assertThat(counter).isEqualTo(TOTAL_QUANTITY);
        assertThat(issuedRows).isEqualTo(TOTAL_QUANTITY);
    }

    @Test
    @DisplayName("Redis + Kafka 적용")
    void issueWithRedisKafka_concurrency() throws InterruptedException {

        //이벤트 오픈 전 쿠폰 정보를 Redis 에 적재
        couponRedisIssueService.prepareEvent(couponId);

        IssueResult result = runConcurrently((couponId, userId) ->
                couponRedisIssueService.issue(couponId, userId));

        //응답은 이미 나갔고, DB 반영은 컨슈머가 비동기 진행 중 → 수렴까지 대기
        long waitStart = System.currentTimeMillis();
        long deadline = waitStart + 30_000;
        while (issuedCouponRepository.count() < TOTAL_QUANTITY && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        long convergeMillis = System.currentTimeMillis() - waitStart;

        int counter = couponRepository.findById(couponId).orElseThrow().getIssuedQuantity();
        long issuedRows = issuedCouponRepository.count();

        printResult("Redis+Kafka", result, counter, issuedRows);
        System.out.printf("  ㄴ DB 반영 완료: API 응답 후 +%dms (컨슈머 비동기 처리)%n", convergeMillis);

        assertThat(result.successCount.get()).isEqualTo(TOTAL_QUANTITY);
        assertThat(result.failCount.get()).isEqualTo(THREAD_COUNT - TOTAL_QUANTITY);
        assertThat(counter).isEqualTo(TOTAL_QUANTITY);
        assertThat(issuedRows).isEqualTo(TOTAL_QUANTITY);
    }

    /*
     * userId 1~1000 이 동시에 같은 쿠폰 발급을 요청하는 상황 재현
     * CountDownLatch 로 전체 요청이 끝날 때까지 대기한다.
     * */
    private IssueResult runConcurrently(BiConsumer<Long, Long> issueCall) throws InterruptedException {

        ExecutorService executorService = Executors.newFixedThreadPool(POOL_SIZE);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        IssueResult result = new IssueResult();

        long startTime = System.currentTimeMillis();

        for (long userId = 1; userId <= THREAD_COUNT; userId++) {
            final long uid = userId;
            executorService.submit(() -> {
                try {
                    issueCall.accept(couponId, uid);
                    result.successCount.incrementAndGet();
                } catch (Exception e) {
                    result.failCount.incrementAndGet();
                    if (e instanceof CannotAcquireLockException) {
                        // MySQL 데드락(Error 1213)을 Spring 이 번역한 예외
                        result.failReasons.merge("데드락", 1, Integer::sum);
                        Throwable root = e;
                        while (root.getCause() != null) root = root.getCause();
                        result.deadlockLog.compareAndSet(null, root.getMessage());
                    } else {
                        result.failReasons.merge(e.getMessage(), 1, Integer::sum);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        result.elapsedMillis = System.currentTimeMillis() - startTime;
        return result;
    }

    private void printResult(String label, IssueResult result, int counter, long issuedRows) {
        System.out.printf("[%s] 성공 %d / 실패 %d / 발급행 %d / 카운터 %d (%dms)%n",
                label, result.successCount.get(), result.failCount.get(),
                issuedRows, counter, result.elapsedMillis);

        if (!result.failReasons.isEmpty()) {
            String reasons = result.failReasons.entrySet().stream()
                    .map(entry -> entry.getKey() + " " + entry.getValue() + "건")
                    .collect(Collectors.joining(", "));
            System.out.printf("  ㄴ 실패 사유: %s%n", reasons);
        }

        if (result.deadlockLog.get() != null) {
            System.out.printf("  ㄴ 데드락 로그: %s%n", result.deadlockLog.get());
        }
    }

    private static class IssueResult {
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        Map<String, Integer> failReasons = new ConcurrentHashMap<>();
        AtomicReference<String> deadlockLog = new AtomicReference<>();
        long elapsedMillis;
    }
}
