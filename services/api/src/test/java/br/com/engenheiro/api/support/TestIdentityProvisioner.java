package br.com.engenheiro.api.support;

import br.com.engenheiro.api.team.IdentityProvisioner;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Provedor de identidade de teste: desligado por padrão (= NÃO CONFIGURADO). Guarda contas por username. */
@Component
public class TestIdentityProvisioner implements IdentityProvisioner {
    public static volatile boolean enabled = false;
    public static volatile boolean down = false;
    /** username → (subject, employeeRef) */
    public static final Map<String, String[]> ACCOUNTS = new ConcurrentHashMap<>();

    public static void reset() {
        enabled = false;
        down = false;
    }

    @Override
    public String name() {
        return "idp-teste";
    }

    @Override
    public boolean configured() {
        return enabled;
    }

    @Override
    public Provisioned createUser(String username, String displayName, String employeeRef) {
        if (down) {
            throw new Unavailable("fora do ar");
        }
        String[] existing = ACCOUNTS.get(username);
        if (existing != null && !existing[1].equals(employeeRef)) {
            throw new UsernameTaken();
        }
        String subject = existing != null ? existing[0] : UUID.randomUUID().toString();
        ACCOUNTS.put(username, new String[] {subject, employeeRef});
        return new Provisioned(TestJwt.ISSUER, subject, "Temp" + UUID.randomUUID().toString().substring(0, 8));
    }
}
