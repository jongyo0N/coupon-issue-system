package com.example.coupon_issue_system.repository;

import com.example.coupon_issue_system.entity.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /*
     * 비관적 락(PESSIMISTIC_WRITE) 조회
     * SELECT ... FOR UPDATE 로 실행되어, 조회 시점에 해당 행의 배타 락(X-Lock)을 획득한다.
     * 락은 트랜잭션이 커밋/롤백될 때 해제되므로 반드시 트랜잭션 안에서 호출해야 한다.
     * */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") Long id);
}
