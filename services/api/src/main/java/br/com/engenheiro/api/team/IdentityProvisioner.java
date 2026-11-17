package br.com.engenheiro.api.team;

/**
 * Cria a conta individual de login no provedor de identidade (ADR-027). Sem credencial de serviço configurada fica
 * NÃO CONFIGURADO: o cadastro do funcionário funciona, só "dar acesso aos apps" é recusado — nunca conta simulada.
 */
public interface IdentityProvisioner {
    String name();

    boolean configured();

    /**
     * Cria (ou retoma, se uma tentativa anterior criou a mesma conta para o mesmo funcionário) a conta com senha
     * temporária que o provedor obriga a trocar no primeiro acesso. A senha volta só nesta resposta; não é guardada.
     */
    Provisioned createUser(String username, String displayName, String employeeRef);

    record Provisioned(String issuer, String subject, String temporaryPassword) {
    }

    /** Nome de usuário já usado por outra pessoa no provedor. */
    class UsernameTaken extends RuntimeException {
        public UsernameTaken() {
            super("username em uso");
        }
    }

    /** Provedor fora do ar ou resposta inesperada: nada é dado como criado. */
    class Unavailable extends RuntimeException {
        public Unavailable(String msg) {
            super(msg);
        }
    }
}
