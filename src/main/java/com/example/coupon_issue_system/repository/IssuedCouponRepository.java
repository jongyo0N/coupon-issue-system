package com.example.coupon_issue_system.repository;

import com.example.coupon_issue_system.entity.IssuedCoupon;
import jakarta.websocket.server.PathParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface IssuedCouponRepository extends JpaRepository<IssuedCoupon, Long> {

    boolean existsByCouponIdAndUserId(Long couponId, Long userId);

    @Query("select ic from IssuedCoupon ic join fetch ic.coupon where ic.userId = :userId")
    List<IssuedCoupon> findAllByUserId(@PathParam("userId") Long userId);
}
