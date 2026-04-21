package com.example.coupon_issue_system.entity.dto;


import com.example.coupon_issue_system.entity.IssuedCoupon;
import com.example.coupon_issue_system.entity.enums.IssuedCouponStatus;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class IssuedCouponDto {
    private Long id;
    private String name;
    private Integer discountAmount;
    private IssuedCouponStatus status;

    public static  IssuedCouponDto from(IssuedCoupon issuedCoupon){
        return IssuedCouponDto.builder()
                .id(issuedCoupon.getId())
                .name(issuedCoupon.getCoupon().getName())
                .discountAmount(issuedCoupon.getCoupon().getDiscountAmount())
                .status(issuedCoupon.getStatus())
                .build();
    }


}
