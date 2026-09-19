FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon -Dorg.gradle.configureondemand=true :server:shadowJar

FROM eclipse-temurin:21-jre
COPY --from=build /src/server/build/libs/wynnvoicechat-relay-*-all.jar /app/server.jar
ENV DB_PATH=/data/voice.db \
    VOICE_REPORT_DIR=/data/voice-reports
VOLUME /data
EXPOSE 9100/tcp 24454/udp 9101/tcp
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
