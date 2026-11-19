# Imagem da API (F16, ADR-030). O jar é construído antes (./mvnw -DskipTests package) e copiado — a imagem não
# compila nada. Base fixada por versão e digest; usuário sem privilégio; sem segredos na imagem (tudo por ambiente).
FROM eclipse-temurin:21.0.13_9-jre-alpine-3.24@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
ARG COMMIT=desconhecido
ARG VERSAO=0.1.0-SNAPSHOT
LABEL org.opencontainers.image.title="engenheiro-api" \
      org.opencontainers.image.vendor="Distribuidora de Cana Engenheiro Ltda" \
      org.opencontainers.image.revision="${COMMIT}" \
      org.opencontainers.image.version="${VERSAO}"
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app && mkdir -p /dados/objetos && chown app:app /dados/objetos
WORKDIR /app
COPY --chown=app:app target/engenheiro-api-${VERSAO}.jar /app/app.jar
ENV ENGENHEIRO_COMMIT=${COMMIT} \
    ENGENHEIRO_VERSAO=${VERSAO} \
    SPRING_PROFILES_ACTIVE=prod \
    ENGENHEIRO_STORAGE_DIR=/dados/objetos \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Dfile.encoding=UTF-8"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
