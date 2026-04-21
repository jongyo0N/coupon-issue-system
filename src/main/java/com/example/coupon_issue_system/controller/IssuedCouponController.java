package com.example.coupon_issue_system.controller;


import com.example.coupon_issue_system.entity.dto.IssuedCouponDto;
import com.example.coupon_issue_system.global.response.ApiResponse;
import com.example.coupon_issue_system.service.IssuedCouponService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@RequestMapping("/api/coupons")
public class IssuedCouponController {

    private final IssuedCouponService issuedCouponService;

    @GetMapping("/users/{userId}")
    public ApiResponse<List<IssuedCouponDto>> getIssuedCouponsByUserId(@PathVariable Long userId) {

        List<IssuedCouponDto> issuedCouponDtoList=issuedCouponService.findAllByUserId(userId);

        return ApiResponse.success(issuedCouponDtoList);
    }
}
