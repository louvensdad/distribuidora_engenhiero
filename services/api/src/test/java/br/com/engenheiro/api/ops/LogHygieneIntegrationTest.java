package br.com.engenheiro.api.ops;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import br.com.engenheiro.api.support.Api;
import br.com.engenheiro.api.support.Fixtures;
import br.com.engenheiro.api.support.Fixtures.Org;
import br.com.engenheiro.api.support.Fixtures.Person;
import br.com.engenheiro.api.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;

/**
 * F16 (OPS-03): requisições reais com token, CPF na busca e no corpo, e uma falha. O log tem uma linha por acesso
 * com rota sem query, status, request_id e ator pseudonimizado — e nunca o token, o CPF ou o corpo.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneIntegrationTest {
    @Autowired
    Environment env;
    @Autowired
    Fixtures fx;

    @Test
    void logDeAcessoSemTokenSemDocumentoComRequestIdEAtorPseudonimizado(CapturedOutput out) {
        Api api = new Api(env.getRequiredProperty("local.server.port", Integer.class));
        Org org = fx.org();
        Person patrao = fx.person(org, org.branch1(), RoleTemplate.ADMIN, "Patrão Fictício");
        String token = patrao.token();
        Api.Res created = api.post("/api/v1/customers", token, Map.of("name", "Cliente Fictício", "personal_document",
                "52998224725", "address", "Rua Exemplo, 1"), (String) null);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = created.json().path("id").asString();
        assertThat(api.get("/api/v1/customers?q=52998224725", token).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/customers/" + id, token).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/customers", "token.invalido.qualquer").status()).isEqualTo(401);

        String log = out.getAll();
        assertThat(log).contains("acesso metodo=POST rota=/api/v1/customers status=201")
                .contains("rota=/api/v1/customers/:id status=200")
                .contains("status=401");
        assertThat(log).doesNotContain(token).doesNotContain("52998224725").doesNotContain("token.invalido.qualquer")
                .doesNotContain(patrao.userId().toString()).doesNotContain("Patrão Fictício");
        assertThat(log).containsPattern("request_id=[0-9a-f-]{36} app=engenheiro-web ator=[0-9a-f]{12}");
    }
}
