# Imagem de runtime. O jar é gerado antes (mvn package) e copiado: build reprodutível no CI e sem Maven na imagem final.
FROM eclipse-temurin:27-jre@sha256:0dd0465f90d398fa3226b56a3973f5d3c47ac323934dceebdab45ac8910bee6e
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin portaria
WORKDIR /app
COPY target/platform-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8081 8082
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
