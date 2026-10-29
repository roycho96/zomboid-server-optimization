# Project Zomboid Server Optimization

Project Zomboid 42.21.0의 호스트 CPU에서 반복 목록 검색과 불필요한 순회를 줄이는 Java 패치입니다. 게임의 **호스트 메뉴를 사용하는 서버장 PC**에 설치합니다. 일반 참가자는 설치할 필요가 없습니다.

[서버 패치 ZIP 다운로드](https://github.com/roycho96/zomboid-server-optimization/releases/latest/download/Zomboid-Server-Patch-Only-42.21-0.6.0.zip) · [측정 결과](PERFORMANCE.md) · [개선 코드](src/roy/pzcore)

## 개선하는 처리

| 처리 | 기존 비용 | 적용한 변경 |
| --- | --- | --- |
| 좀비 송신 요청의 중복 검사 | 요청마다 연결 목록을 처음부터 검색 | 한 번의 postupdate 안에서 객체 identity index를 만들어 재사용 |
| 수신 좀비의 현재 목록 확인 | 같은 좀비도 ArrayList에서 반복 검색 | ID별 이전 위치를 기억하고 현재 그 위치의 객체를 확인한 뒤 사용 |
| 서버 시야 갱신의 방향 계산 | 같은 두 좌표에도 atan2와 각도 정리를 반복 | 한 번의 ServerLOS.updateLOS 안에서 같은 float 입력의 수학 결과만 재사용 |
| 삭제 대상이 없는 목록 정리 | 비어 있는 삭제 집합으로도 removeAll 실행 | 삭제 집합이 비었으면 해당 호출 생략 |
| 패킷 빈도 기록 | 시각 객체와 반복적인 기록 정리 | primitive long 배열로 기록하고 불필요한 전체 정리를 줄임 |
| 서버 좀비 음성 갱신 | 서버에서 효과 없이 끝나는 함수를 각 좀비에 호출 | 현재 게임·PZ Optimizer 구현이 검증된 경우 해당 서버 순회 생략 |

송신 요청 index는 요청 128개 이상에서 사용합니다. 작은 요청·지원하지 않는 목록 형식·클라이언트 역할에서는 원래 검색 경로를 사용합니다. 수신 위치 힌트는 매번 실제 객체를 확인하므로 목록 재정렬과 ID 재사용을 오래된 결과로 판단하지 않습니다.

방향 재사용 범위는 시야 갱신 한 번입니다. 이전 프레임의 좌표나 시야 결과를 보관하지 않습니다. 새로운 좌표만 계속 들어오는 부하에서는 더 느릴 수 있어 각각 끌 수 있습니다.

게임의 AI·시야 규칙·소유권 선택·패킷 형식·전송 주기와 차량 처리는 유지합니다. 기존 GameServer 수신 queue도 유지하며 packetBatching은 항상 false입니다. 이 패키지에는 탄도 모드가 포함되지 않습니다.

## 설치와 업데이트

1. Releases에서 **서버 패치 ZIP**을 받아 폴더에 모두 풉니다.
2. 게임과 호스트를 완전히 종료합니다.
3. 처음 설치하면 Install.cmd, 기존 설치를 바꾸면 Update.cmd를 실행합니다.
4. 게임과 호스트를 다시 시작합니다.

| 파일 | 용도 |
| --- | --- |
| Install.cmd | 처음 설치 |
| Update.cmd | 이전 0.2.0~0.6.0 설치를 확인하고 갱신 |
| Status.cmd | 설치 상태 확인 |
| Uninstall.cmd | 현재 launcher에서 이 패치만 해제 |

기본 게임 폴더는 C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid입니다. 다른 폴더는 다음처럼 지정합니다.

~~~powershell
.\Install.cmd -GameDir "D:\SteamLibrary\steamapps\common\ProjectZomboid"
~~~

자동 설치기는 Windows 게임 폴더의 ProjectZomboid64.json과 두 BAT launcher를 처리합니다. 별도의 전용 서버 launcher를 사용하면 해당 launcher에 같은 classpath가 연결되는지 따로 확인해야 합니다.

원본 게임 jar·PZ Optimizer·heap·GC 설정을 보존하고, 설치된 게임에서 필요한 다섯 class를 로컬 생성합니다. ZIP에는 게임 원본 class나 수정 게임 jar를 배포하지 않습니다. 일반 설치에는 별도의 JDK나 인터넷 연결이 필요하지 않습니다. **Code → Download ZIP은 소스용**이므로 실제 설치에는 Releases의 서버 패치 ZIP을 사용하세요.

실행 파일 변경 중 문제가 생기면 직전 launcher와 설치 기록으로 복구합니다. Uninstall은 현재 JVM 설정과 다른 패치를 보존하고 자신의 classpath 항목만 제거합니다. 이전 jar와 백업은 설치 폴더에 남습니다.

## 지원 범위

- 패치 버전: 0.6.0
- 게임: 검증된 Project Zomboid 42.21.0 파일
- 자동 설치: Windows Steam 게임 폴더
- PZ Optimizer: 선택 사항이며 확인된 jar hash와 실제 구현을 검사
- 참가자 설치: 불필요

같은 버전 표시라도 게임 jar, 다른 Java 패치 또는 유효한 loose class가 달라지면 설치기가 변경 전에 중단할 수 있습니다. 강제로 검사를 건너뛰는 옵션은 제공하지 않습니다. 정확한 지원 hash는 [manifest.json](manifest.json)에 있습니다.

## 설정

설치 폴더의 roy-server-opt/roy-core.properties에서 기능을 각각 끌 수 있습니다. 기존 설정 파일은 보존하고 새 항목이 없으면 기본값을 사용합니다.

~~~properties
emptyRemovalGuard=true
packetRateWindows=true
serverZombieVocalsGuard=true
serverZombieMembershipHints=true
serverZombieRequestIndex=true
serverDirectionMemo=true
packetBatching=false
~~~

예를 들어 방향 재사용만 끄려면 serverDirectionMemo=false를 추가합니다. 설정 변경은 다음 게임·호스트 재시작에 반영됩니다.

## 확인한 결과

실제 게임 class와 Windows 게임 JRE를 사용한 격리 함수 검사에서 다음 값을 기록했습니다.

| 조건 | 기존 p50 → 패치 p50 | 기존 p95 → 패치 p95 |
| --- | --- | --- |
| 송신 요청: 1명·1만 개·빈 큐 | 36.168 → 0.166 ms | 36.719 → 0.203 ms |
| 송신 요청: 8명·1만 개·빈 큐 | 4.772 → 0.171 ms | 5.047 → 0.192 ms |
| 방향 계산: 1만 좌표·8회 반복 | 1032.29 → 337.88 µs | 1074.78 → 343.87 µs |

이 함수 측정은 world·AI·전송 비용을 제외하고 일부 환경을 모의 처리했습니다. 100만 개 서로 다른 방향 입력은 9.48% 느렸으며, 유효한 수치와 회귀 조건을 함께 [PERFORMANCE.md](PERFORMANCE.md)에 기록했습니다.

최종 설치본은 설치 모드 189개와 탄도·측정 모드를 합친 191개 구성에서 가상 참가자 4명·좀비 1만 마리를 60.205초 유지했습니다. 별도의 전체 서버 A/B는 마지막 참가자 이탈과 baseline 지연 증가로 무효 처리했습니다. 따라서 전체 서버 개선율과 실제 참가자 FPS·ping·desync 개선율은 확정하지 않았습니다.

## 소스에서 빌드

Python 3.10 이상, JDK 25, 지원하는 게임 설치가 필요합니다. 원래 릴리스 compiler는 Temurin 25.0.5+8입니다. 다른 compiler의 출력이 달라지면 재검토가 필요합니다.

~~~bash
python build.py --game-dir "/path/to/ProjectZomboid" --jdk "/path/to/jdk-25"
python package.py
python tools/verify_package.py dist/Zomboid-Server-Patch-Only-42.21-0.6.0.zip
~~~

ASM 9.9.1이 없으면 공식 Maven 배포에서 내려받아 기록된 SHA-256을 검사합니다. 게임 jar와 PZ Optimizer는 사용자가 설치한 파일만 읽습니다. 빌드는 자체 helper·recipe와 로컬 생성 class를 검사하고, 패키징은 서버 설치에 필요한 14개 파일만 포함합니다. 생성된 게임 class와 빌드 결과는 Git에서 제외합니다.

재빌드가 끝나면 build/verification.json에 소스 검사와 class 로딩 검증 결과가 남습니다. 이 파일은 로컬 경로를 포함할 수 있어 공개 commit에서 제외합니다.

## 코드와 검증 자료

- [송신 요청 index](src/roy/pzcore/ZombieRequestIndex.java)
- [수신 위치 힌트](src/roy/pzcore/ServerZombieMembership.java)
- [방향 계산 재사용](src/roy/pzcore/ServerDirectionMemo.java)
- [패킷 빈도 기록](src/roy/pzcore/PacketRateWindows.java)
- [로컬 class 생성과 검사](build-src)
- [측정·검사 범위](PERFORMANCE.md)
- [개인 경로와 로그를 제외한 수치 자료](docs/data)

ASM은 BSD 3-Clause 조건으로 사용합니다. [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)와 [전체 라이선스](licenses/ASM-BSD-3-Clause.txt)를 확인하세요.