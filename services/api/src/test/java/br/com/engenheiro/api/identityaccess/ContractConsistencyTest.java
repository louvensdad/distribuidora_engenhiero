package br.com.engenheiro.api.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.identityaccess.domain.FieldClass;
import br.com.engenheiro.api.identityaccess.domain.RoleTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** O catálogo empacotado precisa ser idêntico ao contrato da especificação; modelos só usam o catálogo. */
class ContractConsistencyTest {
    static final Path SPEC = Path.of("../../docs/especificacao/contratos/permissoes.json");
    static final Path PACKAGED = Path.of("src/main/resources/contracts/permissoes.json");

    @Test
    void catalogoEmpacotadoIgualAoContrato() throws Exception {
        assertThat(Files.readString(PACKAGED)).isEqualTo(Files.readString(SPEC));
    }

    @Test
    void modelosUsamSomenteCapacidadesECamposDoContrato() throws Exception {
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(PACKAGED));
        for (RoleTemplate t : RoleTemplate.values()) {
            for (RoleTemplate.TemplateGrant g : t.grants()) {
                JsonNode actions = root.path("resources").path(g.resource());
                Set<String> set = new HashSet<>();
                actions.forEach(a -> set.add(a.asString()));
                assertThat(set).as(t + " -> " + g).contains(g.action());
            }
        }
        Set<String> fields = new HashSet<>();
        root.path("field_classes").forEach(f -> fields.add(f.asString()));
        for (FieldClass f : FieldClass.values()) {
            assertThat(fields).contains(f.key());
        }
        assertThat(root.path("default").asString()).isEqualTo("deny");
        assertThat(root.path("conflict_rule").asString()).isEqualTo("explicit_deny_wins");
    }

    @Test
    void contadorPorPadraoNaoTemDashboardNemAprovacao() {
        assertThat(RoleTemplate.CONTADOR.grants()).noneMatch(g -> g.resource().equals("dashboard")
                || g.action().equals("approve") || g.resource().equals("reports"));
    }
}
