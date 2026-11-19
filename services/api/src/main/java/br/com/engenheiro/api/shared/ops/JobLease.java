package br.com.engenheiro.api.shared.ops;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Concessão de job entre instâncias (F16): {@link #tryAcquire} devolve verdadeiro para UMA instância por intervalo.
 * Um único INSERT … ON CONFLICT … WHERE expirada — sem transação longa, sem trava presa se a instância cair.
 */
@Component
public class JobLease {
    private final JdbcClient jdbc;
    private final String holder = "api-" + UUID.randomUUID();

    public JobLease(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String holder() {
        return holder;
    }

    public boolean tryAcquire(String job, Duration ttl) {
        return jdbc.sql("""
                        INSERT INTO job_leases (name, holder, until) VALUES (:n, :h, now() + make_interval(secs => :s))
                        ON CONFLICT (name) DO UPDATE SET holder = EXCLUDED.holder, until = EXCLUDED.until, updated_at = now()
                         WHERE job_leases.until < now() OR job_leases.holder = EXCLUDED.holder
                        RETURNING holder
                        """).param("n", job).param("h", holder).param("s", (double) ttl.toSeconds())
                .query(String.class).optional().isPresent();
    }
}
