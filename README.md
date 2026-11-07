# 멀티뷰 모니터 (Android)

기존 `multiview-monitor.html`을 WebView 앱으로 감싼 버전. **Google API 키 없이** 동작.

## 구조
```
app/src/main/assets/index.html     ← 화면/기능 (기존 HTML, API 부분만 교체)
app/src/main/java/.../MainActivity.kt  ← WebView + 네이티브 요청 브리지
.github/workflows/build-apk.yml    ← GitHub에서 APK 자동 빌드
```

## APK 만드는 법

### A. Android Studio (PC)
1. Android Studio → **Open** → 이 폴더 선택
2. Gradle 동기화가 끝나면 상단 ▶ (Run) — 폰을 USB 디버깅으로 연결하면 바로 설치
3. APK 파일만 필요하면: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
   → `app/build/outputs/apk/debug/app-debug.apk`

### B. PC 없이 GitHub로 (폰만으로 가능)
1. GitHub에 새 저장소 만들고 이 폴더 내용을 그대로 업로드 (`.github` 폴더 포함)
2. 업로드하면 **Actions** 탭에서 빌드가 자동 시작 (3~5분)
3. 완료된 빌드 → 하단 **Artifacts → multiview-monitor-apk** 다운로드 → 압축 풀고 APK 설치
   (설치 시 "출처를 알 수 없는 앱 허용" 필요)

## API 대신 쓰는 방식
| 기능 | 기존 (Data API) | 지금 |
|---|---|---|
| 제목/채널명/구독자/시청자/시작시간 | videos, channels | watch 페이지 HTML 파싱 |
| 채팅 활동량 | liveChat/messages | 유튜브 웹 채팅이 쓰는 내부 엔드포인트 (`youtubei/v1/live_chat/get_live_chat`) |
| 채널 라이브 감지 | playlistItems + videos | `youtube.com/channel/<ID>/live` 페이지 |
| 검색어로 추가 | search (100유닛) | 검색 결과 페이지 (실시간 필터) |
| 채널 등록 (@핸들 등) | channels / search | 채널 페이지 HTML |

- 할당량 제한 없음. 단, 요청을 너무 자주 하면 유튜브가 잠시 막을 수 있음.
- 비공식 방식이라 유튜브가 페이지 구조를 바꾸면 일부 정보(시청자 수, 구독자 등)가 안 뜰 수 있음.
  → `index.html` 의 `parseWatchPage()` 만 고치면 됨.
- CSV 로그 / 설정 백업은 `다운로드/Multiview/` 폴더에 저장됨.
- 일반 브라우저에서 index.html 을 직접 열면 CORS 때문에 정보 조회가 안 됨 (영상 재생만 됨). 앱 안에서만 동작.
