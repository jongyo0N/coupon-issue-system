package com.example.coupon_issue_system.service;

import com.example.coupon_issue_system.entity.Coupon;
import com.example.coupon_issue_system.entity.IssuedCoupon;
import com.example.coupon_issue_system.entity.dto.IssuedCouponDto;
import com.example.coupon_issue_system.repository.CouponRepository;
import com.example.coupon_issue_system.repository.IssuedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponRepository couponRepository;
    private final IssuedCouponRepository issuedCouponRepository;

    /*
     * 2단계: 비관적 락(SELECT ... FOR UPDATE) 적용 버전
     * 조회 시점에 쿠폰 행의 배타 락을 획득하므로,
     * 같은 쿠폰에 대한 '조회 -> 검증 -> 증가 -> 저장' 구간이 트랜잭션 단위로 직렬화된다.
     * */
    @Transactional
    public IssuedCouponDto issue(Long couponId, Long userId){

        //쿠폰 조회 (행 단위 락 획득, 락 해제 시점 = 트랜잭션 종료)
        Coupon coupon = couponRepository.findByIdForUpdate(couponId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 쿠폰입니다."));

        //발급 가능 여부(상태) 조회
        coupon.isIssuable();

        //수량 검증
        if(coupon.getIssuedQuantity() >= coupon.getTotalQuantity()){
            throw new RuntimeException("선착순 수량이 마감되었습니다.");
        }

        //중복 확인
        if(issuedCouponRepository.existsByCouponIdAndUserId(couponId, userId)){
            throw new RuntimeException("이미 발급된 쿠폰입니다.");
        }

        //발급 수량 증가 (트랜잭션 커밋 시 dirty checking 으로 UPDATE 실행)
        coupon.increaseIssuedQuantity();

        //IssuedCoupon Build
        IssuedCoupon issuedCoupon = IssuedCoupon.builder()
                .coupon(coupon)
                .userId(userId)
                .build();

        return IssuedCouponDto.from(issuedCouponRepository.save(issuedCoupon));
    }

    /*
     * 1단계: 락 미적용 버전 (동시성 비교 실험용)
     * 트랜잭션 경계는 있지만 일반 SELECT 는 스냅샷을 읽을 뿐 행을 잠그지 않으므로,
     * 여러 트랜잭션이 같은 issuedQuantity 를 읽고 서로의 UPDATE 를 덮어쓰는 lost update 가 발생한다.
     * */
    @Transactional
    public IssuedCouponDto issueWithoutLock(Long couponId, Long userId){

        //쿠폰 조회
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 쿠폰입니다."));

        //발급 가능 여부(상태) 조회
        coupon.isIssuable();

        //수량 검증
        if(coupon.getIssuedQuantity() >= coupon.getTotalQuantity()){
            throw new RuntimeException("선착순 수량이 마감되었습니다.");
        }

        //중복 확인
        if(issuedCouponRepository.existsByCouponIdAndUserId(couponId, userId)){
            throw new RuntimeException("이미 발급된 쿠폰입니다.");
        }

        //발급 수량 증가
        coupon.increaseIssuedQuantity();

        //IssuedCoupon Build
        IssuedCoupon issuedCoupon = IssuedCoupon.builder()
                .coupon(coupon)
                .userId(userId)
                .build();

        return IssuedCouponDto.from(issuedCouponRepository.save(issuedCoupon));
    }
}
