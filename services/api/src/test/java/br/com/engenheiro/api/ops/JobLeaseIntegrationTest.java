package br.com.engenheiro.api.ops;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.engenheiro.api.shared.ops.JobLease;
import br.com.engenheiro.api.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** F16: com várias instâncias, um job agendado roda em UMA só por intervalo; expirada, outra assume. */
@IntegrationTest
class JobLeaseIntegrationTest {
    @Autowired
    JdbcClient jdbc;

    @Test
    void oitoInstanciasDisputamESoUmaGanhaAteExpirar() throws Exception {
        List<JobLease> instancias = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            instancias.add(new JobLease(jdbc));
        }
        CyclicBarrier largada = new CyclicBarrier(8);
        var pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> r = new ArrayList<>();
        for (JobLease l : instancias) {
            r.add(pool.submit((Callable<Boolean>) () -> {
                largada.await();
                return l.tryAcquire("teste-disputa", Duration.ofSeconds(2));
            }));
        }
        int vencedores = 0;
        JobLease dono = null;
        for (int i = 0; i < 8; i++) {
            if (r.get(i).get()) {
                vencedores++;
                dono = instancias.get(i);
            }
        }
        pool.shutdown();
        assertThat(vencedores).isEqualTo(1);
        assertThat(dono.tryAcquire("teste-disputa", Duration.ofSeconds(2))).isTrue(); // renova a própria
        JobLease outra = instancias.stream().filter(x -> x != null).filter(x -> !x.holder().equals(
                jdbc.sql("SELECT holder FROM job_leases WHERE name = 'teste-disputa'").query(String.class).single()))
                .findFirst().orElseThrow();
        assertThat(outra.tryAcquire("teste-disputa", Duration.ofSeconds(2))).isFalse();
        jdbc.sql("UPDATE job_leases SET until = now() - interval '1 second' WHERE name = 'teste-disputa'").update();
        assertThat(outra.tryAcquire("teste-disputa", Duration.ofSeconds(2))).isTrue(); // instância caiu: outra assume
    }
}
