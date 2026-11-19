# Imagem do site (F16, ADR-030). O build (npm run build) é feito antes e copiado; base fixada por digest.
FROM nginxinc/nginx-unprivileged:1.30.6-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
ARG COMMIT=desconhecido
LABEL org.opencontainers.image.title="engenheiro-site" org.opencontainers.image.revision="${COMMIT}"
ENV API_UPSTREAM=http://api:8080 NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 NGINX_ENVSUBST_FILTER=API_UPSTREAM|NGINX_LOCAL_RESOLVERS
COPY deploy/default.conf.template /etc/nginx/templates/default.conf.template
COPY dist/ /usr/share/nginx/html/
USER root
RUN printf '{"commit":"%s"}\n' "${COMMIT}" > /usr/share/nginx/html/versao.json && chmod 644 /usr/share/nginx/html/versao.json
USER 101
EXPOSE 8080
