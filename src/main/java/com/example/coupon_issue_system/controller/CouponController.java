package com.example.coupon_issue_system.controller;


import com.example.coupon_issue_system.entity.dto.IssuedCouponDto;
import com.example.coupon_issue_system.global.response.ApiResponse;
import com.example.coupon_issue_system.global.resquest.CouponIssueRequest;
import com.example.coupon_issue_system.service.CouponIssueService;
import com.example.coupon_issue_system.service.IssuedCouponService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@RequestMapping("/api/coupons")
public class CouponController {

    private final IssuedCouponService issuedCouponService;
    private final CouponIssueService couponIssueService;

    @GetMapping("/users/{userId}")
    public ApiResponse<List<IssuedCouponDto>> getIssuedCouponsByUserId(@PathVariable Long userId) {

        List<IssuedCouponDto> issuedCouponDtoList=issuedCouponService.findAllByUserId(userId);

        return ApiResponse.success(issuedCouponDtoList);
    }

    @PostMapping("/issue")
    public ApiResponse<IssuedCouponDto> issueCoupon(@RequestBody CouponIssueRequest requestBody){

        IssuedCouponDto issuedCouponDto = couponIssueService.issue(requestBody.couponId(), requestBody.userId());

        return ApiResponse.success(issuedCouponDto);
    }
}
