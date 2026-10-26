# 선착순 쿠폰 발급 3편 — Redis + Kafka로 판정과 기록을 분리하기

> 실험 환경: Spring Boot 4 / MySQL 8.0 / Redis 7 / Kafka 3.9 (KRaft 단일 노드)
>
> 시나리오: 총 수량 100장 쿠폰에 동시 발급 요청 1,000 / 10,000건 (스레드 풀 32)

## 1. 비관적 락의 한계에서 출발

2편의 `SELECT ... FOR UPDATE`는 정합성을 완벽히 지켰지만, 구조적으로 **쿠폰 행 하나가 전역
직렬화 지점**이다. 모든 요청이 그 행 앞에 한 줄로 서고, 대기하는 동안 DB 커넥션을 쥐고 있다.
트래픽이 커지면 정합성이 아니라 **가용성**이 무너진다 — 커넥션 풀 고갈, 대기 시간 폭증,
무관한 API로의 장애 전파.

근본 원인은 "판정(남았나? 중복인가?)"과 "기록(INSERT/UPDATE)"을 **같은 DB 트랜잭션 안에서**
하고 있다는 것. 3편은 이 둘을 분리한다.

```
요청 ──▶ Redis Lua 스크립트 ──당첨──▶ Kafka 발행 ──▶ 즉시 응답 (~µs 판정)
         (중복+수량 원자 판정)              │
                                          ▼ (비동기)
                              Consumer ──▶ MySQL INSERT + 카운터 UPDATE
```

- **판정은 Redis가**: 메모리 연산이라 마이크로초 단위, DB 락 불필요
- **기록은 Kafka 뒤의 컨슈머가**: 순차 소비라 경합 자체가 없고, 쓰기 부하가 평탄화된다

## 2. Redis — Lua 스크립트로 원자적 판정

```java
private static final RedisScript<Long> ISSUE_SCRIPT = RedisScript.of("""
        if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
            return -1                                        -- 중복
        end
        local total = tonumber(redis.call('GET', KEYS[2]))
        if total == nil then return -2 end                   -- 이벤트 미등록
        local count = tonumber(redis.call('GET', KEYS[3]) or '0')
        if count >= total then return 0 end                  -- 매진
        redis.call('SADD', KEYS[1], ARGV[1])
        redis.call('INCR', KEYS[3])
        return 1                                             -- 당첨
        """, Long.class);
```

핵심은 **Redis가 싱글 스레드로 명령을 처리**하고, **Lua 스크립트는 통째로 하나의 명령처럼
원자 실행**된다는 것. "중복 확인 → 수량 확인 → 증가"라는 read-modify-write 전체가 스크립트
한 번에 들어가므로, 2편에서 DB 행 락으로 만들던 임계 구역이 여기서는 공짜로 생긴다.
락도, 대기도, 데드락도 없다.

쿠폰 메타데이터(총 수량)는 이벤트 오픈 전에 `prepareEvent()`로 Redis에 적재해 두어,
요청 경로에서 MySQL을 한 번도 타지 않게 한다.

### Redis 데이터 구조

| 키 | 타입 | 값 | 읽기/쓰기 명령 | 용도 |
|---|---|---|---|---|
| `coupon:{couponId}:total` | String | 총 수량 (`"100"`) | GET | 매진 판정 기준 (이벤트 오픈 시 적재) |
| `coupon:{couponId}:count` | String | 현재 발급 수 | GET / INCR | 발급 카운터 |
| `coupon:{couponId}:issued-users` | Set | 발급받은 userId 집합 | SISMEMBER / SADD | 중복 발급 판정 |

테스트 실행 후 실제 들어 있는 데이터:

```bash
$ docker exec coupon-redis redis-cli get coupon:33:count
"100"
$ docker exec coupon-redis redis-cli scard coupon:33:issued-users
(integer) 100
$ docker exec coupon-redis redis-cli srandmember coupon:33:issued-users 3
1) "48"  2) "54"  3) "13"
```

지금은 TTL을 걸지 않아 키가 영구히 남는다. 운영이라면 이벤트 종료 시점에 EXPIRE를 걸거나
배치로 정리하는 수명 관리가 필요하다 — 특히 `issued-users` Set은 참여자 수만큼 커지는
키라서(10만 명이면 원소 10만 개) 방치하면 메모리를 계속 점유한다.

## 3. Kafka — 당첨자 기록의 비동기화

```java
// Producer: 당첨 확정 즉시 발행하고 응답
kafkaTemplate.send(TOPIC, String.valueOf(couponId), couponId + ":" + userId);

// Consumer: 순차 소비하며 DB 반영
@KafkaListener(topics = TOPIC, groupId = "coupon-issue-group")
@Transactional
public void consume(String message) {
    if (issuedCouponRepository.existsByCouponIdAndUserId(couponId, userId)) return; // 멱등
    couponRepository.increaseIssuedQuantity(couponId);  // UPDATE ... SET n = n + 1
    issuedCouponRepository.save(...);
}
```

설계 포인트 세 가지.

1. **메시지 key = couponId** — 같은 쿠폰의 메시지는 같은 파티션에 들어가 순서가 보장된다.
2. **카운터는 원자적 UPDATE** (`SET issued_quantity = issued_quantity + 1`) — 읽지 않고
   증가시키므로 lost update가 원천 불가능. 컨슈머를 여러 개로 늘려도 안전하다.
   (2편의 "읽은 값 + 1 덮어쓰기"와 대비되는 지점)
3. **멱등 컨슈머** — Kafka는 at-least-once 전달이라 같은 메시지가 두 번 올 수 있다.
   `existsByCouponIdAndUserId`로 이미 처리한 메시지는 건너뛴다.

## 4. 실측 결과

| 동시 요청 | 비관적 락 (응답=DB확정) | Redis+Kafka API 응답 | Redis+Kafka DB 수렴 | 발급 수 |
|---|---|---|---|---|
| 1,000건 | 686ms | **105ms (6.5×)** | +1,922ms | 둘 다 정확히 100 |
| 10,000건 | 3,221ms | **295ms (10.9×)** | +107ms | 둘 다 정확히 100 |

관찰 포인트.

- **요청이 많아질수록 격차가 벌어진다.** 비관적 락은 전 요청이 DB 직렬화 구간을 통과하지만,
  Redis 판정은 10,000건을 295ms에 끝냈다(~34,000 req/s). 판정 처리량이 DB가 아니라
  Redis/네트워크에 의해 결정되기 때문.
- **DB 수렴이 1,000건 실험에서 더 느린(+1,922ms) 이유**는 첫 실행이라 컨슈머 그룹 조인·파티션
  할당이 포함됐기 때문이다. 워밍업된 10,000건 실험에서는 +107ms — 당첨 100건은 요청 폭주
  초반에 이미 결정되므로, 컨슈머는 API 응답이 끝나기 전에 거의 다 따라잡는다.
- **DB에는 당첨자 100건의 쓰기만 도달한다.** 낙첨 9,900건은 Redis에서 소멸. 2편에서는
  10,000건 전부가 DB 락 경쟁에 참여했다.

## 5. 공짜가 아니다 — 트레이드오프

- **최종 일관성(eventual consistency).** 사용자는 "발급 완료" 응답을 받지만 DB에는 아직
  없을 수 있다. 응답 직후 "내 쿠폰함"을 조회하면 비어 있을 수 있다 — UX·API 설계가
  "접수됨" 의미론을 감당해야 한다.
- **정합성의 원천이 Redis로 이동했다.** Redis가 날아가면 "누가 당첨됐는지"의 기준이
  사라진다. AOF/RDB 영속화와 복제 구성이 전제돼야 하고, Redis 카운터와 DB 발급 수의
  불일치를 맞추는 보정(재처리/대사) 설계가 필요하다.
- **장부가 두 개다.** Redis 당첨 ≠ DB 발급 완료. Kafka 발행 실패, 컨슈머 지연·실패 구간에서
  두 장부는 어긋난다(멱등 처리와 재시도로 수렴시키는 구조).
- **운영 비용.** MySQL 하나로 끝나던 시스템에 Redis와 Kafka 두 인프라가 추가됐다.
  트래픽이 비관적 락으로 감당되는 수준이라면 2편이 더 싼 정답이다.

## 6. 실행 방법

```bash
docker compose up -d        # mysql + redis + kafka
./gradlew test --tests '*.CouponIssueConcurrencyTest'
```

출력 예시:

```
[비관적 락] 성공 100 / 실패 9900 / 발급행 100 / 카운터 100 (3221ms)
[락 미적용] 성공 218 / 실패 9782 / 발급행 218 / 카운터 100 (1907ms)
ㄴ 실패 사유: 선착순 수량이 모두 매진되었습니다. 8894건, 데드락 888건
[Redis+Kafka] 성공 100 / 실패 9900 / 발급행 100 / 카운터 100 (295ms)
ㄴ DB 반영 완료: API 응답 후 +107ms (컨슈머 비동기 처리)
```
