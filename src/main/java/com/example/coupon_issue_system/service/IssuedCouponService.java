package com.example.coupon_issue_system.service;

import com.example.coupon_issue_system.entity.dto.IssuedCouponDto;
import com.example.coupon_issue_system.repository.IssuedCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service
@RequiredArgsConstructor
public class IssuedCouponService {

    private final IssuedCouponRepository issuedCouponRepository;

    /*
    * description: 사용자 보유 쿠폰 목록
    * api:/api/coupons/users/{userId}
    * */
    @Transactional
    public List<IssuedCouponDto> findAllByUserId(Long userId) {

        return issuedCouponRepository.findAllByUserId(userId).stream()
                .map(IssuedCouponDto::from)// entity -> dto
                .toList();
    }
}
