package br.com.engenheiro.api.shared.idempotency;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Comandos de estoque/financeiros com `Idempotency-Key` (contratos/CONVENCOES_E_TRANSICOES).
 * Roda dentro da transação do comando: a chave é reservada por INSERT na chave primária, então uma segunda
 * chamada concorrente com a mesma chave espera a primeira terminar e devolve o resultado gravado.
 * Se o comando falhar, a reserva da chave some junto com o rollback.
 */
@Service
public class IdempotencyService {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public IdempotencyService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record Stored(UUID actorUserId, String requestHash, Integer responseStatus, String responseBody) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(Actor actor, String operation, String key, Object request, Class<T> type,
                         Supplier<T> command) {
        validateKey(key);
        String hash = hash(operation, request);
        int claimed = jdbc.sql("""
                        INSERT INTO idempotency_keys (organization_id, operation, idem_key, actor_user_id, request_hash)
                        VALUES (:org, :op, :key, :actor, :hash)
                        ON CONFLICT DO NOTHING
                        """)
                .param("org", actor.organizationId()).param("op", operation).param("key", key)
                .param("actor", actor.userId()).param("hash", hash).update();
        if (claimed == 0) {
            Stored s = jdbc.sql("""
                            SELECT actor_user_id, request_hash, response_status, CAST(response_body AS text) AS response_body
                              FROM idempotency_keys WHERE organization_id = :org AND operation = :op AND idem_key = :key
                            """)
                    .param("org", actor.organizationId()).param("op", operation).param("key", key)
                    .query(Stored.class).single();
            if (!Objects.equals(s.actorUserId(), actor.userId()) || !s.requestHash().equals(hash)
                    || s.responseBody() == null) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "Esta chave de idempotência já foi usada com outro conteúdo.");
            }
            return json.readValue(s.responseBody(), type);
        }
        T result = command.get();
        jdbc.sql("""
                        UPDATE idempotency_keys SET response_status = 200, response_body = CAST(:body AS jsonb)
                         WHERE organization_id = :org AND operation = :op AND idem_key = :key
                        """)
                .param("body", json.writeValueAsString(result)).param("org", actor.organizationId())
                .param("op", operation).param("key", key).update();
        return result;
    }

    private static void validateKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Cabeçalho Idempotency-Key obrigatório (16 a 128 caracteres: letras, números, . _ : -).");
        }
    }

    private String hash(String operation, Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (operation + "\n" + json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
