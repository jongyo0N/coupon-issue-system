package com.example.coupon_issue_system.service;

import com.example.coupon_issue_system.entity.Coupon;
import com.example.coupon_issue_system.entity.IssuedCoupon;
import com.example.coupon_issue_system.repository.CouponRepository;
import com.example.coupon_issue_system.repository.IssuedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CouponIssueService {

    private final CouponRepository couponRepository;
    private final IssuedCouponRepository issuedCouponRepository;

    public void issue(Long couponId, Long userId){

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

        issuedCouponRepository.save(issuedCoupon);
    }
}
