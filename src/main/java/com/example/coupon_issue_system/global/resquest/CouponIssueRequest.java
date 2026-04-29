package com.example.coupon_issue_system.global.resquest;

public record CouponIssueRequest(
        Long userId,
        Long couponId
) {}
