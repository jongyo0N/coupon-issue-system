# 선착순 쿠폰 발급 2편 — 행 단위 락(SELECT ... FOR UPDATE)으로 조회 시점부터 잠그기

> 실험 환경: Spring Boot 4 (Hibernate ORM 7) / MySQL 8.0 (InnoDB, 기본 격리수준 REPEATABLE READ) / HikariCP 기본 설정
>
> 시나리오: **총 수량 100장** 쿠폰에 **서로 다른 유저 1000명이 동시 발급 요청** (스레드 풀 32)

## 1. 지난 편 요약: @Transactional은 왜 lost update를 막지 못하는가

`@Transactional`이 보장하는 것은 **트랜잭션 경계**다. 메서드 안의 작업들이 하나의 단위로
커밋되거나 롤백된다는 원자성이지, "다른 트랜잭션과 동시에 실행되어도 안전하다"는 보장이 아니다.

쿠폰 발급 로직의 본질은 **읽고(Read) → 판단하고(Modify) → 쓰는(Write)** 3단계다.

```java
Coupon coupon = couponRepository.findById(couponId).orElseThrow(...); // Read
if (coupon.getIssuedQuantity() >= coupon.getTotalQuantity()) { ... }  // Modify(판단)
coupon.increaseIssuedQuantity();                                      // Write(커밋 시 UPDATE)
```

문제는 MySQL(InnoDB, REPEATABLE READ)에서 **일반 SELECT는 행을 잠그지 않는다**는 것.
MVCC 덕분에 각 트랜잭션은 자신이 시작된 시점의 **스냅샷**을 읽는다(consistent nonlocking read).
덕분에 읽기끼리는 서로를 막지 않아 빠르지만, 동시에 들어온 두 트랜잭션이 **같은 값을 읽는 것도
막지 못한다**.

```
T1: issuedQuantity = 50 읽음 ─┐
T2: issuedQuantity = 50 읽음 ─┤  둘 다 스냅샷에서 50을 본다
T1: UPDATE ... issued_quantity = 51, 커밋
T2: UPDATE ... issued_quantity = 51, 커밋   ← T1의 +1이 증발 (lost update)
```

여기서 중요한 디테일: JPA의 dirty checking이 만드는 UPDATE는 `SET issued_quantity = issued_quantity + 1`
같은 상대값 연산이 아니라, **메모리에서 계산된 절대값**(`SET issued_quantity = 51`)을 쓴다.
실험 중 잡힌 실제 쿼리를 보면 그대로 드러난다.

```sql
update coupon set coupon_status=0, ..., issued_quantity=99, ... where id=11
```

UPDATE 문 자체는 실행 시점에 행의 배타 락(X lock)을 잡는다. 하지만 **이미 읽기가 끝난 뒤**다.
"잠금 없이 읽은 값"을 기반으로 "절대값을 덮어쓰기" 때문에, 늦게 커밋한 쪽이 먼저 커밋한 쪽의
증가분을 지워버린다. 트랜잭션이 아무리 잘 감싸져 있어도 소용없다.

그래서 이번 편의 전략은 하나다. **읽는 순간부터 그 행을 잠가버리자.**

## 2. 해결 전략: SELECT ... FOR UPDATE (비관적 락)

`SELECT ... FOR UPDATE`는 **locking read**다. 일반 SELECT와 두 가지가 다르다.

1. **조회하는 행에 배타 락(X lock)을 건다.** 다른 트랜잭션의 `FOR UPDATE` 조회, UPDATE, DELETE는
   이 락이 풀릴 때까지 대기한다. 락은 **트랜잭션이 커밋/롤백될 때** 해제된다.
2. **스냅샷이 아니라 최신 커밋 값을 읽는다**(current read). 락을 기다리다 깨어난 트랜잭션은
   방금 커밋된 따끈한 값을 본다. REPEATABLE READ의 스냅샷 규칙에 대한 의도적 예외다.

이 두 성질이 합쳐지면 "조회 → 검증 → 증가 → 저장" 전체가 쿠폰 행 하나를 기준으로 한
**임계 구역(critical section)** 이 된다.

```
T1: SELECT ... FOR UPDATE  → X 락 획득, 99 읽음
T2: SELECT ... FOR UPDATE  → 대기...
T1: 검증 통과 → 100으로 UPDATE → 커밋 (락 해제)
T2: (깨어남) → 최신 값 100을 읽음 → "선착순 수량이 마감되었습니다" ← 정확히 거절
```

낙관적 락(버전 충돌 후 재시도)과 대비해 "일단 잠그고 시작"하므로 **비관적 락(pessimistic lock)**
이라 부른다. 충돌이 드물면 낭비지만, 선착순 이벤트처럼 **한 행에 쓰기가 집중되는 워크로드**에서는
충돌이 확정적이므로 비관적 접근이 자연스럽다.

## 3. 구현

### 3-1. Repository — @Lock 어노테이션

```java
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.id = :id")
    Optional<Coupon> findByIdForUpdate(@Param("id") Long id);
}
```

- `LockModeType.PESSIMISTIC_WRITE`를 지정하면 Hibernate가 DB dialect에 맞는 locking read
  구문을 생성한다. MySQL에서 실제로 나간 SQL:

  ```sql
  select c1_0.id, c1_0.coupon_status, ..., c1_0.total_quantity
  from coupon c1_0
  where c1_0.id = ?
  for update of c1_0
  ```

- `PESSIMISTIC_READ`를 쓰면 공유 락(`FOR SHARE`)이 나간다. 공유 락은 "읽는 동안 수정만 막는"
  용도라 읽고-수정-쓰는 우리 시나리오에는 맞지 않고, 뒤에서 볼 데드락 패턴(S→X 승급 경쟁)을
  오히려 유발한다. **읽은 뒤 수정할 거라면 처음부터 배타 락**이 원칙이다.

### 3-2. Service — @Transactional이 이제 필수가 되는 이유

```java
@Transactional
public IssuedCouponDto issue(Long couponId, Long userId) {

    // 조회 시점에 X 락 획득 — 여기서부터 커밋까지가 임계 구역
    Coupon coupon = couponRepository.findByIdForUpdate(couponId)
            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 쿠폰입니다."));

    coupon.isIssuable();                       // 상태 검증

    if (coupon.getIssuedQuantity() >= coupon.getTotalQuantity()) {
        throw new RuntimeException("선착순 수량이 마감되었습니다.");  // 수량 검증
    }

    if (issuedCouponRepository.existsByCouponIdAndUserId(couponId, userId)) {
        throw new RuntimeException("이미 발급된 쿠폰입니다.");        // 중복 검증
    }

    coupon.increaseIssuedQuantity();           // 커밋 시 dirty checking → UPDATE

    IssuedCoupon issuedCoupon = IssuedCoupon.builder()
            .coupon(coupon).userId(userId).build();

    return IssuedCouponDto.from(issuedCouponRepository.save(issuedCoupon));
}
```

1편에서는 `@Transactional`이 "있어도 못 막는" 조연이었지만, 이번에는 **없으면 동작 자체가 안
되는** 주연이 된다.

- **락의 수명 = 트랜잭션의 수명.** 커밋/롤백 시점에 락이 풀리므로, 트랜잭션 경계가 곧 임계
  구역의 경계다. `@Transactional`이 없으면 Hibernate는 `TransactionRequiredException`
  (no transaction is in progress)을 던진다.
- 부수 효과로 **중복 검증도 안전해졌다.** 같은 쿠폰에 대한 요청은 전부 직렬화되므로, 같은
  유저가 동시에 두 번 눌러도 `existsByCouponIdAndUserId` 검사가 경쟁 없이 순서대로 평가된다.
  (락 미적용에서는 이 검사도 뚫린다. 다만 유저별 중복의 정석은 `(coupon_id, user_id)` 유니크
  제약이고, 이는 다음 편들에서 다룬다.)

### 3-3. 동시성 테스트

```java
@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",   // 1000건 요청 시 SQL 로그 폭주 방지
        "logging.level.org.hibernate.orm.jdbc.bind=info"
})
class CouponIssueConcurrencyTest {

    private static final int TOTAL_QUANTITY = 100;
    private static final int THREAD_COUNT = 1000;
    private static final int POOL_SIZE = 32;

    // userId 1~1000이 같은 쿠폰에 동시 발급 요청
    private IssueResult runConcurrently(BiConsumer<Long, Long> issueCall) throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(POOL_SIZE);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        ...
        for (long userId = 1; userId <= THREAD_COUNT; userId++) {
            executorService.submit(() -> {
                try {
                    issueCall.accept(couponId, uid);
                    result.successCount.incrementAndGet();
                } catch (Exception e) {
                    result.failCount.incrementAndGet();
                    result.failReasons.merge(e.getClass().getSimpleName(), 1, Integer::sum);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        ...
    }
}
```

테스트 작성 시 주의한 점 두 가지.

- **테스트 메서드에 `@Transactional`을 붙이면 안 된다.** 테스트 트랜잭션이 setUp에서 만든
  쿠폰을 커밋하지 않은 채 들고 있으므로, 별도 스레드에서 열린 트랜잭션들은 그 쿠폰을 볼 수
  없다. 동시성 테스트는 각 호출이 독립 트랜잭션으로 실제 커밋을 해야 의미가 있다.
- **실제 MySQL로 돌린다**(`docker compose up -d`). 락 경합, MVCC, 데드락 감지는 모두 InnoDB의
  동작이다. 인메모리 DB로는 이번 편의 핵심 현상이 재현되지 않는다.

## 4. 실험 결과

같은 테스트를 락 미적용(`issueWithoutLock`, `@Transactional`만 적용)과 비관적 락(`issue`)
버전에 각각 돌렸다.

| | 락 미적용 (@Transactional만) | 비관적 락 (FOR UPDATE) |
|---|---|---|
| 성공한 요청 | **210건** | **100건** |
| 발급된 쿠폰 행 수 (`issued_coupon`) | **210장** — 110장 초과 발급 | **100장** — 정확 |
| 쿠폰 카운터 (`coupon.issuedQuantity`) | 100 | 100 |
| 실패 사유 | `CannotAcquireLockException` 790건 (**데드락**) | `RuntimeException`("마감") 900건 |
| 소요 시간 | 576ms | 675ms |

### 4-1. 카운터는 거짓말을 한다

락 미적용 결과에서 가장 섬뜩한 부분은 초과 발급 그 자체가 아니라, **카운터가 100으로 멀쩡해
보인다**는 점이다. `increaseIssuedQuantity()`는 "읽은 값 + 1"을 쓰고, 읽은 값이 100 이상이면
예외를 던지므로 카운터는 구조적으로 100을 넘을 수 없다. 대시보드에는 "100/100 발급 완료,
정상 마감"으로 보이지만 실제 발급 테이블에는 210장이 쌓여 있다. **지표와 실제 데이터가
어긋나는 버그는 모니터링으로도 잡히지 않는다.**

### 4-2. 예상 밖의 손님: 데드락 790건

락을 안 걸었는데 실패 사유가 전부 "락을 얻지 못함"이라는 것도 아이러니하다. MySQL의
`SHOW ENGINE INNODB STATUS`에 남은 데드락 로그를 보자.

```
*** (1) TRANSACTION:
update coupon set ..., issued_quantity=99, ... where id=11

*** (1) HOLDS THE LOCK(S):
RECORD LOCKS ... index PRIMARY of table `coupon_db`.`coupon`
lock mode S locks rec but not gap          ← coupon 행의 공유 락을 쥐고 있고

*** (1) WAITING FOR THIS LOCK TO BE GRANTED:
RECORD LOCKS ... index PRIMARY of table `coupon_db`.`coupon`
lock_mode X locks rec but not gap waiting  ← 같은 행의 배타 락을 기다리는 중

*** (2) TRANSACTION:  (상대편도 완전히 동일한 상태)
```

범인은 **외래 키 제약**이다. 락 미적용 버전의 트랜잭션 내 SQL 순서를 보면:

```
① SELECT coupon          — 잠금 없음 (스냅샷 읽기)
② SELECT issued_coupon   — 중복 확인, 잠금 없음
③ INSERT issued_coupon   — FK(coupon_id) 검증을 위해 InnoDB가
                            부모 coupon 행에 공유 락(S)을 건다 (커밋까지 유지)
④ 커밋 직전 flush: UPDATE coupon — 배타 락(X) 필요
```

T1과 T2가 둘 다 ③을 통과하면 **둘 다 coupon 행의 S 락을 쥔 상태**가 된다. 이어서 둘 다
④에서 X 락을 요구하는데, X 락은 상대방의 S 락과 호환되지 않는다. 서로가 서로를 기다리는
S→X **락 승급 경쟁(conversion deadlock)** 이다. InnoDB가 이를 감지해(Error 1213
`Deadlock found when trying to get lock`) 한쪽을 롤백시키고, Spring은 이를
`CannotAcquireLockException`으로 번역한다. 32개 스레드가 쉴 새 없이 이 패턴에 빠진 결과가
790건의 희생이다.

씁쓸한 포인트: 790건이 데드락으로 "우연히" 죽어준 덕분에 초과 발급이 210장에서 멈췄다.
데드락마저 없었다면 1000장 가까이 발급됐을 것이다. **안전장치가 아니라 또 다른 장애가
정합성 붕괴를 가려준 것뿐이다.**

### 4-3. 비관적 락은 데드락도 없앤다

비관적 락 버전의 SQL 순서는 락의 방향이 반대다.

```
① SELECT coupon FOR UPDATE — 처음부터 배타 락(X) 획득
② SELECT issued_coupon     — 중복 확인
③ INSERT issued_coupon     — FK 검증의 S 락: 내가 이미 X 락 보유 → 즉시 통과
④ UPDATE coupon            — 이미 보유한 X 락 → 즉시 통과
```

가장 강한 락을 **가장 먼저, 한 번에** 잡기 때문에 S→X 승급이라는 중간 상태 자체가 사라진다.
트랜잭션들은 ①에서 한 줄로 서고, 결과는 깔끔하다 — 정확히 100장 발급, 900건은 데드락이
아닌 **비즈니스 예외("마감")로 정상 거절**, 데드락 0건.

성능도 짚어보자. 1000건 처리에 576ms → 675ms. 완전 직렬화를 강제했는데도 차이가 작은
이유는 **락 보유 시간(트랜잭션 길이)이 몇 ms로 짧기 때문**이다. 비관적 락의 비용은 "락을
거는 행위"가 아니라 "락을 쥐고 있는 시간 × 대기열 길이"다.

## 5. 비관적 락의 비용과 한계

이번 실험에서는 비용이 작아 보였지만, 조건이 나빠지면 급격히 악화된다.

- **쿠폰 행 하나가 전역 직렬화 지점이 된다.** 처리량의 상한은 `1 / 트랜잭션당 락 보유 시간`.
  트랜잭션 안에서 외부 API 호출, 푸시 발송 같은 느린 작업을 하면 대기열이 폭발한다.
  **락 구간 안에는 DB 작업 최소한만 남겨야 한다.**
- **대기 중에도 DB 커넥션을 점유한다.** 락 대기는 커넥션을 쥔 채 이루어진다. HikariCP 기본
  풀(10개)이 락 대기자로 가득 차면, 쿠폰과 무관한 다른 API까지 커넥션을 못 얻어 장애가
  전파된다.
- **대기 시간 상한은 `innodb_lock_wait_timeout`(기본 50초).** 초과 시 lock wait timeout
  에러가 난다. 기다리기 싫다면 Hibernate 힌트로 제어할 수 있다 —
  `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))`은
  `FOR UPDATE NOWAIT`(즉시 실패), `value = "-2"`는 `FOR UPDATE SKIP LOCKED`(잠긴 행 건너뛰기,
  선착순 재고를 여러 행으로 쪼갰을 때 유용)를 생성한다.
- **락은 인덱스 레코드에 걸린다.** 지금은 PK 단건 조회라 정확히 한 행만 잠기지만, 인덱스를
  타지 않는 조건으로 locking read를 하면 스캔한 레코드 전부(+갭)가 잠긴다. locking read의
  WHERE 조건은 반드시 인덱스를 타는지 확인해야 한다.
- **단일 DB 안에서만 유효하다.** InnoDB의 행 락이므로 DB가 샤딩되거나 캐시(Redis) 레이어에서
  수량을 관리하기 시작하면 이 방식만으로는 부족하다.

## 6. 정리

- `@Transactional`은 원자성(경계)을 줄 뿐, 일반 SELECT는 스냅샷을 읽기 때문에 read-modify-write
  경쟁(lost update)을 막지 못한다.
- `@Lock(LockModeType.PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`는 **조회 시점에 배타 락**을
  잡고 **최신 커밋 값**을 읽게 해, 조회부터 커밋까지를 행 단위 임계 구역으로 만든다.
- 실측: 100장 한정에 1000명 동시 요청 → 락 미적용은 210장 발급(그마저 데드락 790건이 가려준
  결과), 비관적 락은 정확히 100장.
- 덤으로 FK 공유 락 때문에 생기던 S→X 승급 데드락까지 사라졌다. "가장 강한 락을 가장 먼저"가
  동시성뿐 아니라 데드락 예방 관점에서도 유효했다.
- 비용은 직렬화와 커넥션 점유. 락 보유 시간이 짧을 때만 저렴하다. 충돌이 드문 워크로드,
  혹은 DB 락 자체를 피하고 싶은 상황에서의 대안(낙관적 락, 원자적 UPDATE, 분산 락/Redis)은
  다음 편에서 다룬다.
