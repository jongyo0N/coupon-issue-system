package com.example.coupon_issue_system.repository;

import com.example.coupon_issue_system.entity.Coupon;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /*
     * 원자적 증가 UPDATE (read-modify-write 를 DB 한 문장으로 축약)
     * 읽지 않고 바로 'n = n + 1' 을 실행하므로 lost update 가 발생할 수 없다.
     * */
    @Modifying
    @Query("update Coupon c set c.issuedQuantity = c.issuedQuantity + 1 where c.id = :id")
    int increaseIssuedQuantity(@Param("id") Long id);
}
