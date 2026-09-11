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
