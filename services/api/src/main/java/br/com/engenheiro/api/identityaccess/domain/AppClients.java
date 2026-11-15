package br.com.engenheiro.api.identityaccess.domain;

import java.util.Set;

/** Clients OIDC das cinco superfícies. Identificam o app; não concedem permissão. */
public final class AppClients {
    public static final String WEB = "engenheiro-web";
    public static final String GESTAO = "engenheiro-gestao";
    public static final String RETIRADA = "engenheiro-retirada";
    public static final String ENTREGAS = "engenheiro-entregas";
    public static final String CONTABIL = "engenheiro-contabil";
    public static final Set<String> ALL = Set.of(WEB, GESTAO, RETIRADA, ENTREGAS, CONTABIL);

    private AppClients() {
    }
}
