# 실행 전용 이미지 — 빌드는 CI 러너가 한다(gradle 캐시를 러너에서 재사용하기 위해).
# CD 워크플로우가 build/libs의 부트 jar을 app.jar로 복사해 두고 이 파일을 빌드한다.
FROM eclipse-temurin:17-jre-jammy

# 컨테이너가 죽어도 로그로 원인을 찾을 수 있게 시간대를 서비스 기준으로 고정한다.
ENV TZ=Asia/Seoul \
    JAVA_OPTS="-Xms256m -Xmx768m"

RUN groupadd --system spring && useradd --system --gid spring spring
USER spring

WORKDIR /app
COPY build/libs/app.jar app.jar

EXPOSE 8080

# exec 형태가 아니라 sh -c 인 이유: JAVA_OPTS를 배포 환경(.env)에서 덮어쓸 수 있어야 한다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
