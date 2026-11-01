---
name: relay-apk-taildrop
description: Relay Android 개발 중 “apk 보내줘”, “APK 폰으로 보내줘”, “Taildrop으로 보내줘” 요청을 처리한다. 개발 중인 Relay 소스의 기존 릴리스 키로 서명한 APK를 검증해 지정된 Android 폰에 전달한다. 설명·점검 요청만으로 전송하거나 폰에 설치하지 않는다.
---

# Relay APK Taildrop

사용자의 “보내줘”는 전송 권한이다. 파일과 대상이 확정되면 반복 확인 없이 진행한다. 폰 설치·제거·데이터 초기화·런타임 테스트는 포함하지 않는다.

## 소스와 빌드

- 현재 소스의 AGENTS.md를 따른다. 다른 채팅·PR·worktree를 지정한 요청은 해당 소스와 산출물을 확인한다. 현재 checkout의 과거 APK를 대신 보내지 않는다.
- 기본 빌드는 `./gradlew assembleRelease lintRelease --console=plain`이다. 실패 시 남아 있는 APK를 보내지 않는다. 사용자가 기존 APK를 지정하면 해당 파일을 검증해 사용한다.
- 세션에 debug만 있다면 같은 소스에서 signed release를 빌드한다고 알린다. 현재 debug는 release와 동일한 `com.hush.voice`에 다른 서명을 쓰므로 기존 설치본과 충돌한다. debug 명시 요청은 이 점을 설명하고 의도를 확인한다. 제거·다른 앱 ID·키 변경으로 자동 해결하지 않는다.
- `com.hush.voice`, `hush.db`, assistant component와 기존 릴리스 서명을 유지한다. 서명 키가 없거나 검증에 실패하면 unsigned/debug로 대체하지 않는다. versionCode를 임의로 수정하거나 다운그레이드하지 않는다.
- worktree에서 서명 파일이 없으면 `git rev-parse --path-format=absolute --git-common-dir`의 부모인 원본 checkout의 로컬 `signing/`을 확인한다. 필요하면 worktree `signing/`을 해당 디렉터리로 연결한다. `signing/` ignore 패턴은 심볼릭 링크를 제외하지 않을 수 있으므로 `git check-ignore`로 확인하고 Git common directory의 로컬 `info/exclude`에 `/signing`을 추가한다. 기존 파일을 덮어쓰지 않는다. 비밀번호·개인키를 출력하거나 Git·스킬에 복제하지 않는다.
- SDK 경로는 local.properties 또는 ANDROID_HOME에서 로컬로 읽는다. 실제 사용자 경로를 스킬·예시·PR에 적지 않는다.
- 관련 앱 코드 수정의 검증은 AGENTS.md를 따른다. 사용자 개인 폰에서 instrumentation 테스트를 실행하지 않는다.

## 파일·대상 검증과 전송

helper는 이 파일 옆 `scripts/send_apk.py`다. `--repo`는 선택한 소스 worktree, `--apk`는 그 안의 APK를 지정한다. 기본 실행은 점검만 한다.

```sh
python3 <skill-dir>/scripts/send_apk.py --repo <source-worktree> --apk app/build/outputs/apk/release/app-release.apk
```

출력된 SHA-256과 소스를 확인하고, 실제 전송 요청이 있을 때 아래를 실행한다. helper는 파일을 별도로 복사하고 그 복사본을 검증·전송한다.

```sh
python3 <skill-dir>/scripts/send_apk.py --repo <source-worktree> --apk app/build/outputs/apk/release/app-release.apk --expected-sha256 <점검한-해시> --send
```

기기 선택은 Git common directory의 `info/relay-taildrop.json`에 로컬로 둔다. 필드는 `peer_id`, `dns_name`, `certificate_sha256`이다. 실제 기기 이름·주소·식별자와 인증서 지문은 이 로컬 설정에서만 읽고 추적 파일·PR·원격 로그에 기록하지 않는다. helper는 안정 식별자·DNS·Android·동일 소유자·온라인·Taildrop 대상 여부를 확인하고 최신 IP를 사용한다. 식별자가 달라지면 다른 폰으로 우회하지 말고 새 기기 선택을 확인한다.

설정이 없으면 로컬에서 기기를 선택하고 기존 릴리스 인증서를 확인해 설정한다. 실제 값이 든 예시·설정·영수증을 커밋하지 않는다.

폰 설치본의 인증서·버전은 Taildrop으로 조회할 수 없으므로 업데이트 호환성을 보장했다고 말하지 않는다.

## 결과

대상은 “설정된 Android 기기”처럼 일반 명칭으로 보고하고 소스 revision, 파일명, 버전, APK SHA-256과 전송 결과를 보고한다. 실제 기기 이름·DNS·IP·노드 ID·인증서 지문·사용자 절대 경로는 콘솔이나 원격에 공유할 결과에서 제외한다. 명령 성공은 파일 전달 완료이며 설치 완료가 아니다. 수신 알림 또는 Files의 다운로드 목록에서 APK를 확인하고 기존 앱을 제거하지 않고 업데이트하도록 안내한다.

실패는 자동 반복하지 않는다. 타임아웃이면 수신 여부가 불명확하다고 알리고 수신 확인 후 재시도한다. 영수증은 무시되는 `app/build/taildrop/last-transfer.json`에 남는다.
