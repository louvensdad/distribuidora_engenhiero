# Imagem do site (F16, ADR-030). O build (npm run build) é feito antes e copiado; base fixada por digest.
FROM nginxinc/nginx-unprivileged:1.31.7-alpine@sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e
ARG COMMIT=desconhecido
LABEL org.opencontainers.image.title="engenheiro-site" org.opencontainers.image.revision="${COMMIT}"
ENV API_UPSTREAM=http://api:8080 NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 NGINX_ENVSUBST_FILTER=API_UPSTREAM|NGINX_LOCAL_RESOLVERS
COPY deploy/default.conf.template /etc/nginx/templates/default.conf.template
COPY dist/ /usr/share/nginx/html/
USER root
# Correções de segurança do Alpine e do nginx publicadas depois da imagem base (F16: varredura Trivy).
RUN apk upgrade --no-cache >/dev/null
RUN printf '{"commit":"%s"}\n' "${COMMIT}" > /usr/share/nginx/html/versao.json && chmod 644 /usr/share/nginx/html/versao.json
USER 101
EXPOSE 8080
