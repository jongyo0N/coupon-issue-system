package com.example.coupon_issue_system.repository;

import com.example.coupon_issue_system.entity.CouponUsageHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponUsageHistoryRepository extends JpaRepository<CouponUsageHistory, Long> {
}
