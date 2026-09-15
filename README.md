# AdCheck

온라인 건강기능식품 상품페이지의 광고 표현을 소비자가 구매 전에 확인할 수 있도록 돕는 Chrome Extension 프로젝트입니다.

현재는 실제 의료적·법률적 판단이나 AI 분석을 제공하지 않습니다. Backend의 개발용 `MockClaimAnalyzer`로 Extension과 API의 전체 흐름을 검증합니다.

## Repository structure

- `backend/`: Spring Boot 분석 API
- `extension/`: Manifest V3 Chrome Extension

## Backend

필수 환경변수 `DB_USERNAME`, `DB_PASSWORD`를 설정한 후 실행합니다. `DB_URL` 기본값은 `jdbc:postgresql://localhost:5432/adcheck`입니다.

```powershell
cd backend
./gradlew clean build
./gradlew bootRun
```

분석 API는 `POST http://localhost:8080/api/v1/analyses`입니다.

## Extension

```powershell
cd extension
npm install
npm run build
```

Chrome의 `chrome://extensions`에서 Developer mode를 켜고 `extension/dist` 폴더를 Load unpacked로 선택합니다.

Backend URL을 변경해야 한다면 `extension/.env`에 다음 값을 설정한 뒤 다시 빌드합니다.

```text
VITE_API_BASE_URL=http://localhost:8080
```

## Local fixture

저장소 루트에서 다음 명령으로 테스트 상품페이지를 열 수 있습니다.

```powershell
python -m http.server 4173 --directory extension/fixtures
```

그 후 `http://localhost:4173/product-page.html`에서 AdCheck Side Panel을 실행합니다.

## Rule Engine

DB 기반 Rule 선택·보수적 평가·공식 출처 조회 서비스의 실행 및 통합 방법은 [Rule Engine 개발 문서](docs/rule-engine.md)를 참고하세요. 기존 분석 API는 계속 `MockClaimAnalyzer`를 사용합니다.

---

## 브랜치 전략

### 기준 브랜치
`feature/analysis-orchestration`이 현재 팀의 통합 기준 브랜치입니다.
Rule Engine 판정, 차등 TTL, AI 4개 블록(추출/원료매칭/RAG/비교),
Finding 조립까지 모두 여기에 포함되어 있으며, 전체 테스트가 통과하는
안정된 상태입니다. `main`은 아직 여기로 병합되지 않았습니다 —
Extension 연동까지 실제로 확인되면 팀 논의 후 병합 예정입니다.

### 작업 시작 규칙
새 기능/수정 작업을 시작할 때는 `feature/analysis-orchestration`에서
바로 작업하지 말고, 아래처럼 그 지점에서 새 브랜치를 파서 진행해주세요.

```bash
git checkout feature/analysis-orchestration
git pull origin feature/analysis-orchestration
git checkout -b feature/작업내용을-설명하는-이름
```

**이렇게 하는 이유:**
- `feature/analysis-orchestration`은 여러 명이 동시에 보는 공유 기준점이라,
  누군가 이 브랜치에서 직접 작업하다가 커밋이 꼬이면 다른 팀원 전체가
  영향을 받습니다.
- 각자 별도 브랜치에서 작업하면, 완성 전까지는 서로의 작업이 충돌 없이
  독립적으로 진행되고, 완성된 뒤에만 안전하게 다시 합칠 수 있습니다.

### 현재 진행 중인 브랜치
| 브랜치 | 작업 내용 | 담당 |
|---|---|---|
| `feature/analysis-orchestration` | 기준 브랜치 (직접 작업 금지) | - |
| `feature/extension-real-api-connection` | useAdCheck.ts 실제 API 연결 (Mock 제거) | 유주원 |
| (작업 시작 시 이름 추가) | DOM/Image payload 확정 | 이효정 |

작업 시작하실 때 어느 브랜치에서 진행하시는지 위 표에 추가해주시면
서로 겹치는 파일 없이 진행할 수 있습니다.
