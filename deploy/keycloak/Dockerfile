# Keycloak "otimizado" para a produção: a etapa `build` do Quarkus roda AQUI (na construção da imagem), não na primeira subida.
# Assim o contêiner em execução não grava em /opt/keycloak e pode rodar com sistema de arquivos somente leitura.
# As opções de build (banco e saúde) têm de ser as mesmas do `start --optimized` do docker-compose.prod.yml.
FROM quay.io/keycloak/keycloak:26.8 AS builder
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=false
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
