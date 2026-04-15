package com.example.coupon_issue_system.entity;

import com.example.coupon_issue_system.entity.enums.CouponStatus;
import com.example.coupon_issue_system.entity.enums.CouponType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private CouponType couponType; // FIXED_AMOUNT, PERCENTAGE

    private Integer totalQuantity; // 총 발급 가능 수량

    private Integer issuedQuantity; // 현재 발급된 수량

    private LocalDateTime startDate;

    private LocalDateTime endDate;

    private CouponStatus couponStatus;


    @Builder
    public Coupon(String name, CouponType couponType, Integer totalQuantity, LocalDateTime startDate, LocalDateTime endDate) {
        this.name = name;
        this.couponType = couponType;
        this.totalQuantity = totalQuantity;
        this.issuedQuantity = 0;
        this.couponStatus = CouponStatus.ACTIVE;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    // 비즈니스 로직: 수량 증가
    public void incrementIssuedQuantity() {
        this.issuedQuantity++;
    }

    // 발급 가능 여부 확인
    public boolean isAvailable() {
        LocalDateTime now = LocalDateTime.now();
        return now.isAfter(startDate) && now.isBefore(endDate) && issuedQuantity < totalQuantity;
    }
}