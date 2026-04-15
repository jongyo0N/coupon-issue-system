package com.example.coupon_issue_system.entity;


import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class CouponUsageHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "issued_coupon_id")
    private IssuedCoupon issuedCoupon;

    private Long userId;
    private Long orderId;
    private LocalDateTime usedAt;

    @Builder
    public CouponUsageHistory(IssuedCoupon issuedCoupon, Long userId, Long orderId, Integer discountAmount) {
        this.issuedCoupon = issuedCoupon;
        this.userId = userId;
        this.orderId = orderId;
        this.usedAt = LocalDateTime.now();
    }

}
