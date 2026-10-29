Project Zomboid 42.21 서버 처리 최적화 0.6.0

이 ZIP에는 서버 패치 설치·업데이트·해제에 필요한 파일만 있습니다. 탄도 모드는 Workshop의 Ballistics MP로 별도 설치합니다.

처음 설치: Install.cmd
기존 0.2.0~0.6.0 업데이트: Update.cmd
설치 확인: Status.cmd
해제: Uninstall.cmd

ZIP을 폴더에 모두 풀고 게임과 호스트를 종료한 뒤 실행합니다. 다음 재시작부터 적용됩니다. 다른 게임 경로는 명령 파일에 -GameDir "게임 폴더"를 전달하세요. 서버장 또는 전용 서버 PC만 설치합니다. 일반 참가자는 설치하지 않습니다.

좀비 요청 목록 중복 검색과 반복 방향 계산, 빈 삭제 집합 검사, rate 기록 관리, 효과 없는 서버 음성 순회를 최적화합니다. AI·시야 규칙·소유권 선택·패킷 형식과 전송 주기·차량 보정은 변경하지 않습니다. packetBatching은 false입니다.

원본 게임과 PZ Optimizer jar, heap·GC 설정을 보존합니다. 설치된 게임에서 필요한 class를 로컬 생성하므로 별도 JDK·인터넷은 필요하지 않습니다. 지원하지 않는 게임·PZ Optimizer·loose class 변경은 launcher를 수정하기 전에 거부합니다.

roy-server-opt\roy-core.properties의 serverZombieRequestIndex와 serverDirectionMemo 등 각 기능은 false로 끌 수 있습니다. 기존 파일에 항목이 없으면 기본값을 사용합니다. Uninstall은 현재 launcher에서 자기 패치 항목만 제거하고 백업과 이전 jar는 보존합니다.

최종 설치본은 191모드·가상 참가자4명·좀비1만 상태를60초 유지했습니다. 실제 참가자 FPS·ping·멀티 사격 검증과 다르며 전체 서버 개선율이나 모든 렉·desync 제거를 보장하지 않습니다.

ASM 9.9.1은 BSD 3-Clause 조건으로 포함합니다. THIRD_PARTY_NOTICES.txt와 licenses/ASM-BSD-3-Clause.txt를 확인하세요.
