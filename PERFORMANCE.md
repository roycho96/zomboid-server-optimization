# 서버 처리 측정과 검증

측정 대상은 Project Zomboid 42.21.0의 서버 패치 0.6.0입니다. 2026-10-29에 같은 Windows 호스트의 게임 JRE에서 조건을 교차 측정하고 baseline을 다시 실행했습니다. 아래 함수 실험과 실제 서버 실행은 서로 다른 범위입니다.

## 송신 요청 목록

baseline은 최종 artifact에서 원래 Packer.postupdate를 복원한 코드입니다. 기존 수신 목록 최적화는 양쪽에 유지했습니다. 후보는 배포된 ZombieRequestIndex와 변환된 실제 postupdate를 사용했습니다. 소유권 선택·world·전송은 모의 처리했고 reset 시간은 측정에서 제외했습니다.

| 조건 | baseline p50 / p95 | 후보 p50 / p95 |
| --- | --- | --- |
| 1명·1만 개·빈 큐 | 36.1675 / 36.7187 ms | 0.1660 / 0.2031 ms |
| 8명·1만 개·빈 큐 | 4.7719 / 5.0473 ms | 0.1711 / 0.1922 ms |

1만 개 조건의 추가 임시 할당은 약 131KB입니다. 128개 미만 fallback은 추가 할당 없이 약 39~171ns가 늘었습니다. 관련 JIT 전환이 끝난 뒤 공통 warmup을 수행한 최종 29행 중 28행만 canary 5% 기준을 통과했습니다. 빈 0개 control 한 행의 5.12% drift는 무효로 남겼습니다.

p50·p95·IQR·할당량·원시 시간 표본은 [request-benchmark.json](docs/data/request-benchmark.json)에 있습니다.

## 방향 계산

baseline은 원래 Vector2.getDirection 수학 경로입니다. 후보는 실제 배포 helper와 class 바이트를 사용하고 ServerLOS·서버 역할 환경은 모의 처리했습니다. 배열 생성과 범위 시작 등 일부 준비는 timed 구간 밖입니다. 입력 버퍼는 각 timed batch에서 반복됩니다.

| 조건 | baseline p50 / p95 | 후보 p50 / p95 |
| --- | --- | --- |
| 1만 좌표·8회 반복·고정 | 1032.29 / 1074.78 µs | 337.88 / 343.87 µs |
| 1만 좌표·8회 반복·25% 변경 | 1522.10 / 1537.65 µs | 669.78 / 686.10 µs |
| 1만 좌표·8회 반복·100% 변경 | 1531.83 / 1546.60 µs | 1516.21 / 1538.23 µs |
| 100만 개 서로 다른 입력 | 19412.35 / 19652.75 µs | 21252.10 / 21600.70 µs |
| 범위 밖 8만 호출 | 243.60 / 246.76 µs | 256.97 / 258.95 µs |

고정 배열은 약 832KiB입니다. timed 구간의 추가 할당은 양쪽 0이었습니다. 12행 모두 canary를 통과했지만 입력 반복이 적으면 이득이 작거나 회귀합니다.

자료의 worker-fallback 행은 같은 owner thread에서 역할 조건을 바꾼 control입니다. 실제 다른 thread의 성능 측정으로 해석하지 않습니다. 실제 다른 thread에서 cache를 쓰지 않는 동작은 semantic 검사에 포함했습니다.

전체 자료는 [direction-benchmark.json](docs/data/direction-benchmark.json)에 있습니다.

## 실제 서버 실행

최종 설치본과 PZ Optimizer, 설치 모드 189개 및 탄도·측정 모드를 합친 191개 구성을 사용했습니다. 기본 Sandbox와 Muldraugh stock map이며 모드 맵의 선택형 지역은 활성화하지 않았습니다. 가상 참가자는 렌더러와 참가자 Lua 없이 같은 PC의 loopback으로 연결했습니다.

| 최종 설치본 확인 | 값 |
| --- | --- |
| 참가자 / 좀비 | 4명 / 1만 마리 |
| 일정한 부하 유지 | 60.205초 |
| main CPU 중앙값 | 234.024 ms/s |
| 5초 창별 tick 중앙값의 중앙값 | 100 ms |
| 5초 창별 tick p95의 중앙값 | 111 ms |

CPU는 준비 뒤 첫 10초와 마지막 2초를 제외한 48개 표본입니다. tick은 8개 창의 통계이며 전체 tick의 p95가 아닙니다. 이는 비교군 없는 짧은 동작 확인입니다. [runtime-smoke.json](docs/data/runtime-smoke.json)에 조건과 범위를 기록했습니다.

같은 JVM에서 A0/B0/A1/B1/A2/B2/A3를 반복한 별도 실험은 마지막 A3에 참가자가 4명에서 2명으로 줄고 좀비 수도 바뀌어 무효 처리했습니다. 앞선 baseline의 tick 지연도 계속 늘었습니다. 활성 구간에서 약 100ms를 관측했지만 이 실험으로 전체 서버 개선 배수를 제시하지 않습니다. [full-server-comparison.json](docs/data/full-server-comparison.json)에 각 구간과 무효 판정을 남겼습니다.

## 동작 검증

| 검사 | 결과 | 범위 |
| --- | --- | --- |
| 기존 수신 목록·codec | 환경별 256,832 assertions·368 snapshots, 3개 환경 | 실제 game class, 일부 모의 환경 |
| 송신 요청 목록·codec | Windows/Linux 각각 2,740 assertions·1,368 snapshots | 실제 helper·postupdate, 소유권 선택과 전송은 모의 처리 |
| 방향 계산 | 234,413 assertions | float bit 동일성·중첩·예외·역할·thread·할당 실패 후 재시도 |
| 기존 설치·갱신·해제·실패 복구 | 27개 경우·217 checks | Windows 게임 JRE와 격리된 파일 fixture |
| 로컬 설치 확인 | 28 checks | 원본·classpath·8GB heap·생성 class·기능 설정 |

설치 fixture의 기존 동반 backend 보존 2개 경우·10 checks는 217개에 포함되므로 중복 합산하지 않습니다. 과거 버전의 실제 generated jar를 사용하는 migration fixture는 게임 class 재배포를 피하기 위해 이 공개 저장소에 포함하지 않았습니다.

실제 사람의 멀티 사격, 친구 호스트 환경, 원격 지연·손실 복구·장시간 소유권 전환과 참가자 FPS는 별도 확인이 필요합니다. 검사 수와 함수 속도를 실제 플레이의 개선율로 환산하지 않습니다.