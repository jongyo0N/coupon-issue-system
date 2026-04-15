package com.example.coupon_issue_system.entity;

import com.example.coupon_issue_system.entity.enums.IssuedCouponStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "issued_coupon")
public class IssuedCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private IssuedCouponStatus status;

    private LocalDateTime issuedAt;

    private LocalDateTime expiredAt;

    @Builder
    public IssuedCoupon(Coupon coupon, Long userId) {
        this.coupon = coupon;
        this.userId = userId;
        this.status = IssuedCouponStatus.ISSUED;
        this.issuedAt = LocalDateTime.now();
        this.expiredAt = LocalDateTime.now().plusDays(7);
    }
}
