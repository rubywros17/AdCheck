# AdCheck
### 건강기능식품 과대광고 판정 AI · Chrome Extension
 
> 건강기능식품 상세페이지의 광고 표현을 AI로 분석하고 **공식 인정 기능성과 비교**해,  
> 소비자가 구매 전에 **주의할 표현과 그 근거**를 확인할 수 있도록 돕는 Chrome Extension
<img width="960" height="540" alt="image" src="https://github.com/user-attachments/assets/601c9892-975e-45b9-ad82-14017678df32" />

[![Demo](https://img.shields.io/badge/YouTube-시연_영상-FF0000?style=for-the-badge&logo=youtube&logoColor=white)](https://youtu.be/ZaLkQ302obs)

## 💡 프로젝트 소개
 
건강기능식품 상세페이지의 광고 표현을 AI로 분석하고 공식 인정 기능성과 비교해, 소비자가 구매 전에 주의할 표현과 그 근거를 확인할 수 있도록 돕는 Chrome Extension입니다.
 
온라인 건강기능식품 광고에는 공식 인정 기능성보다 효과를 강하게 표현하거나, 질병 예방·치료 효과가 있는 것처럼 받아들여질 수 있는 문구가 실제로 유통되고 있습니다.
2026년 식약처 온라인 부당광고 합동점검에서는 **225건**이 적발되었고, 그중 **건강기능식품 오인·혼동(46%)** 과 **질병 예방·치료 오인(37%)** 이 대부분을 차지했습니다.
 
기존 대응은 **사후 적발 공고**(구매 이후)나 **판매자 대상 사전 심의**(B2B)에 머물러, 구매자가 결제 직전에 스스로 확인할 방법이 없었습니다.
AdCheck는 **구매 순간, 보고 있는 상품 페이지에서 클릭 한 번으로** 확인이 필요한 표현과 이유·근거를 보여줍니다.
 
<br>

## ✨ 주요 기능
 
- 🔍 **현재 페이지 분석** — 상세페이지의 텍스트·이미지 수집 (iframe 상세영역 포함)
- 🤖 **AI Claim 추출** — OCR + Gemini로 효능·기능성 주장 식별
- 🧪 **공식 기능성 매칭** — 식약처 공식 데이터로 제품·원료·인정 기능성 확정
- ⚖️ **과장·오인 가능성 판정** — 공통 30 + 원료별 41개 규칙 (코드 로직 · 정규식 · AI 평가)
- 📚 **근거 검색 (RAG)** — 심의기준·판례에서 관련 문단 검색
- 💬 **결과 표시** — HIGH · CAUTION 심각도, 쉬운 설명, 근거 법령 링크
- 🖍️ **원문 하이라이트** — 문제 문구의 원래 위치로 이동
<br>

## 🔄 Service Flow
 
`상품 상세페이지 접속` → `사이드패널에서 분석하기` → `텍스트·이미지 수집` → `AI · 공식 데이터 · 규칙 · RAG 분석` → `주의 표현 · 이유 · 근거 확인` → `원문 위치로 이동`
 
<br>

## 🔍 분석 파이프라인
 
> 요청이 오면 먼저 **정규화 URL + 콘텐츠 해시 + 파이프라인 버전**으로 재사용 가능한 결과가 있는지 확인하고(있으면 즉시 200), 없으면 PENDING으로 저장한 뒤 비동기 Job에서 아래 단계를 실행합니다(202 + 폴링).
 
1. **AI#1 추출** — 이미지 OCR(Google Vision, 실패 시 Gemini 폴백) 후 Gemini 1회 호출로 Claim/제품 후보/원료 후보/위험 신호를 추출합니다.
2. **공식 제품·원료·기능성 확정** — 신고번호·제품명으로 공식 Product를 식별하고, 확정된 원료의 공식 인정 기능성을 조회합니다.
3. **표시란 인용 필터** — 확정 원료의 공식 문구를 그대로 옮긴 문장은 광고가 아니라 표시 의무 이행이므로 판정 대상에서 제외합니다.
4. **규칙 판정** — COMMON 규칙과 원료별 규칙을 평가해 MATCHED / REVIEW_REQUIRED / NOT_MATCHED로 분류합니다.
5. **근거 검색(RAG) + AI#2 비교** — MATCHED된 Claim에 한해 공식 근거 문단을 검색하고, 공식 기능성과의 의미 차이를 비교해 소비자용 설명을 생성합니다.
6. **Finding 조립** — 판정된 규칙 전체를 보존하면서, 대표 위험도·근거로 화면에 표시할 Finding을 구성합니다.
자세한 설계는 [`docs/rule-engine.md`](docs/rule-engine.md)를, 실제로 겪은 문제와 해결 과정은 [`docs/troubleshooting.md`](docs/troubleshooting.md)를 참고하세요.
 
<br>

## 🛠 Tech Stack
 
### Backend
 
![Java](https://img.shields.io/badge/Java_21-007396?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_4-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![JPA](https://img.shields.io/badge/Spring_Data_JPA-59666C?style=for-the-badge&logo=hibernate&logoColor=white)
 
### Database
 
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&logo=flyway&logoColor=white)
 
### Extension
 
![React](https://img.shields.io/badge/React-20232A?style=for-the-badge&logo=react&logoColor=61DAFB)
![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white)
![Vite](https://img.shields.io/badge/Vite-646CFF?style=for-the-badge&logo=vite&logoColor=white)
![Chrome](https://img.shields.io/badge/Manifest_V3-4285F4?style=for-the-badge&logo=googlechrome&logoColor=white)
 
### AI
 
![Gemini](https://img.shields.io/badge/Gemini_API-8E75B2?style=for-the-badge&logo=googlegemini&logoColor=white)
![Cloud Vision](https://img.shields.io/badge/Cloud_Vision_OCR-4285F4?style=for-the-badge&logo=googlecloud&logoColor=white)
![RAG](https://img.shields.io/badge/RAG-In--Memory_Search-4B5563?style=for-the-badge)
 
| 영역 | 기술 |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1, PostgreSQL, Flyway, Spring Data JPA |
| Extension | React, TypeScript, Vite, Chrome Extension Manifest V3 |
| AI | Google Gemini API (Claim 추출·규칙 평가·비교 설명·임베딩), Google Cloud Vision (OCR 기본 엔진) |
 
<br>

## 🗂 Data
 
- 🏷️ **식품안전나라** — 건강기능식품 품목제조 신고사항 (제품 45,970건)
- 🧪 **식품안전나라** — 기능성 원료 인정현황 · 고시형 원료 (원료 607건, 개별인정형 773건, 고시형 96건, 동의어 3,302건)
- 🔗 **제품-원료 사전 매핑** — 176,447건 (동의어 버전 불일치 해소를 위해 전체 재검증)
- ⚖️ **식약처 「식품등의 부당한 표시 또는 광고의 내용 기준」 · 한국건강기능식품협회 광고심의 기준** — 판정 규칙 71개, 근거 문서 15건
- 📰 **광고 심의 사례** — 38건
  
<br>

## 🎯 MVP Scope
 
- **건강기능식품** 상세페이지 1개를 대상으로 한 단일 페이지 분석
- **텍스트 + 상세 이미지** 분석 (영상·숏폼은 향후 확장)
- 공식 기능성 비교 + 규칙 판정 + 근거 제시 + 원문 위치 이동
- 비로그인 사용, 최근 점검 기록은 브라우저 로컬에 최근 20건
> ⚠️ 분석 결과는 광고의 법적 위반 여부를 확정하는 것이 아니라,  
> **소비자가 어떤 표현을 왜 다시 확인해야 하는지 이해하도록 돕는 정보**입니다.

<br>

**이 서비스가 하지 않는 것**
- 광고의 법적 위반 여부를 확정하지 않습니다.
- 질병 치료 효과나 제품의 의학적 효능을 판정하지 않습니다.
- 제품의 안전성을 확정하지 않습니다.
- 존재하지 않는 공식 제품번호·기능성·법적 근거를 임의로 만들어내지 않습니다.
화면에는 "확인이 필요한 표현", "공식 인정 기능성보다 강한 표현일 가능성이 있습니다"처럼, 단정이 아니라 왜 다시 확인해야 하는지를 보여주는 문구를 사용합니다.
 
<br>

## 📈 성능 개선
 
| 항목 | 이전 → 이후 | 방법 |
|---|---|---|
| 이미지 글자 추출 | 약 44초 → **12.6초** | OCR 엔진을 Gemini → Google Cloud Vision으로 교체 (추출 글자수 약 96% 유지) |
| 규칙 평가 | 5.9초 → **2.9초** (약 52% 단축) | 규칙 평가 동시성 4 → 12 |
| 반복 분석 | 같은 요청 재분석 → **즉시 응답** | 정규화 URL + 콘텐츠 해시 기반 결과 재사용 (위반 7일 / clean 30일) |
 
<br>

## 📁 Repository structure
 
```
backend/    Spring Boot 4 (Java 21) 분석 API
extension/  Manifest V3 Chrome Extension (React + TypeScript + Vite)
docs/       Rule Engine 설계, 트러블슈팅 기록, 판정 편차 실측 로그
```
 
<br>

## 🚀 실행 방법
 
### Backend
 
#### 필수 환경변수
 
`backend/.env` 파일을 만들어 아래 값을 설정합니다(`.gitignore`에 포함되어 있어 커밋되지 않습니다).
 
```env
DB_USERNAME=...
DB_PASSWORD=...
GEMINI_API_KEY=...
GOOGLE_VISION_API_KEY=...
```
 
- `DB_URL` 기본값은 `jdbc:postgresql://localhost:5432/adcheck`이며, `DB_USERNAME`/`DB_PASSWORD`는 기본값이 없어 반드시 설정해야 합니다.
- `GEMINI_API_KEY`가 없으면 실제 분석 파이프라인(Claim 추출, 규칙 평가, 비교 설명)이 동작하지 않습니다.
- `GOOGLE_VISION_API_KEY`는 없어도 동작하지만(Gemini로 자동 폴백), **설정을 강력히 권장합니다** — 이미지 문자 추출이 Gemini 단독 대비 훨씬 빠르고(실측 기준 분석 1건이 10배 가까이 단축), Gemini 무료 티어 호출 한도(분당 15회)에 주는 부담도 줄어듭니다.
#### 실행
 
```powershell
cd backend
./gradlew.bat clean build     # 전체 빌드 + 테스트
./gradlew.bat bootRun         # API 실행
./gradlew.bat test            # 전체 테스트 실행
```
 
분석 API는 다음 두 엔드포인트입니다.
 
```
POST /api/v1/analyses           분석 요청 (재사용 가능한 결과가 있으면 즉시 200, 없으면 202 + analysisId)
GET  /api/v1/analyses/{id}      상태·결과 조회 (PENDING / PROCESSING / COMPLETED / FAILED)
```
 
분석은 비동기로 처리되며, 완료까지 두 번째 엔드포인트를 폴링해야 합니다. Gemini 무료 티어는 분당 15회 한도라, **분석을 1분 이내에 두 번 이상 연달아 돌리면 429가 발생**하고 재시도 대기만큼 처리 시간이 늘어납니다 — 성능을 확인할 때는 최소 1분 간격을 두세요.
 
#### 참조 데이터(Rule/Product/Ingredient 등)
 
`rule`, `product`, `adcase`, `reference` 패키지는 `data/` 디렉터리의 CSV로 시딩되는 참조·조회 전용 데이터입니다. `data/`는 저장소에 포함돼 있지 않으므로, 로컬에서 이 데이터를 처음 구성하려면 팀 내부 공유 경로를 통해 CSV를 받아 배치하세요.
 
### Extension
 
```powershell
cd extension
npm install
npm run build     # tsc --noEmit → vite build
```
 
`chrome://extensions`에서 Developer mode를 켜고 `extension/dist` 폴더를 Load unpacked로 선택합니다.
 
Backend 주소를 바꿔야 한다면 `extension/.env`(`.env.example` 복사)에 아래 값을 설정한 뒤 다시 빌드합니다.
 
```text
VITE_API_BASE_URL=http://localhost:8080
```
 
#### 로컬 테스트용 픽스처
 
```powershell
python -m http.server 4173 --directory extension/fixtures
```
 
`http://localhost:4173/product-page.html`을 열고 Side Panel에서 분석을 실행합니다.
 
<br>

## ⚠️ 알려진 한계
 
실사용 검증 과정에서 발견했고 아직 해결하지 않은 것들을 투명하게 남겨둡니다.
 
- **AI#1 Claim 추출이 완전히 재현되지는 않습니다.** 같은 입력을 5회 반복하면 하나의 Claim이 평균 2.5~3.3회(50~65%) 나타납니다. 페이지를 바꿔도 이 비율은 거의 그대로입니다(`docs/rule-judge-instability-log.md`).
- **REVIEW_REQUIRED는 규칙 자체의 위험도로 표시되어**, 실제 위반과 같은 등급(HIGH)으로 보일 수 있습니다. 판단을 보류한 것과 확정 위반을 화면에서 구분하는 작업이 필요합니다.
- **마켓(쇼핑몰)마다 텍스트 추출 품질이 다릅니다.** 카페24 계열·네이버는 안정적이지만, 일부 마켓은 본문 텍스트가 거의 추출되지 않아 OCR에 크게 의존하거나(G마켓), 텍스트·이미지 상한(각 300개·60장)에 근접해 뒷부분이 잘릴 수 있습니다(쿠팡).
- **재사용 캐시는 이미지 URL이 같고 내용만 바뀌는 경우를 감지하지 못합니다.** 콘텐츠 해시가 상품명·본문 텍스트·이미지 URL/alt로만 구성되어 있기 때문입니다.
- **광고 추적 파라미터(`campaignid`, `gclid` 등)가 URL 정규화에서 제거되지 않아**, 같은 상품이라도 유입 경로가 다르면 재사용 캐시가 맞지 않을 수 있습니다(`docs/troubleshooting.md` 백로그).
  
<br>

## 👥 Team
 
| 이름 | 역할 |
|---|---|
| 유주원 | Backend + Leader |
| 이효정 | Frontend + Data |
| 서호준 | AI |
| 송하은 | Design + Frontend |
 
