package br.com.engenheiro.api.team;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F17: o nome enviado ao IdP não pode travar a criação da conta (o Keycloak recusa parênteses, &, <, etc.). */
class IdpNameTest {
    @Test
    void nomeComCaracterRecusadoPeloIdpViraNomeLimpoSemPerderAcentoNemApostrofo() {
        assertThat(KeycloakIdentityProvisioner.idpName("Paula do Balcão (ensaio)", "u")).isEqualTo("Paula do Balcão ensaio");
        assertThat(KeycloakIdentityProvisioner.idpName("João D'Ávila & Filhos <x>", "u")).isEqualTo("João D'Ávila Filhos x");
        assertThat(KeycloakIdentityProvisioner.idpName("Ana-Maria  Souza Jr.", "u")).isEqualTo("Ana-Maria Souza Jr.");
        assertThat(KeycloakIdentityProvisioner.idpName("((( )))", "ensaio.balcao")).isEqualTo("ensaio.balcao");
    }
}
