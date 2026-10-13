# ============ 1단계: 빌드 ============
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# (1) 빌드 도구 먼저 복사 → 의존성 캐시
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew dependencies --no-daemon > /dev/null

# (2) 소스 복사 → 실행 jar만 빌드
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ============ 2단계: 실행 ============
FROM eclipse-temurin:21-jre
WORKDIR /app

# (3) root가 아닌 사용자로 실행
RUN useradd --system --no-create-home appuser

# (4) 1단계에서 만든 jar만 가져오기
COPY --from=build /app/build/libs/adcheck-backend-0.0.1-SNAPSHOT.jar app.jar

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]