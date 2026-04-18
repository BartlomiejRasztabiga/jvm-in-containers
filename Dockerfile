FROM eclipse-temurin:24-jdk AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN ./gradlew dependencies --no-daemon -q
COPY src src
RUN ./gradlew bootJar --no-daemon -q

FROM eclipse-temurin:24-jdk
WORKDIR /app
RUN mkdir /dumps

COPY --from=builder /app/build/libs/*.jar app.jar

ENV JAVA_TOOL_OPTIONS="\
  -XX:MaxRAMPercentage=70.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:MaxMetaspaceSize=256m \
  -XX:NativeMemoryTracking=summary \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/dumps/heap.hprof \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:stdout:time,uptime,level,tags"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
