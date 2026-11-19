package br.com.engenheiro.api.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retentativa segura (F08): consulta periodicamente as notas com envio em curso/incerto. Só CONSULTA o provedor —
 * nunca reenvia. Sem provedor configurado não faz nada.
 */
@Component
public class FiscalResolutionJob {
    private static final Logger log = LoggerFactory.getLogger(FiscalResolutionJob.class);
    private final InvoiceService invoices;
    private final br.com.engenheiro.api.shared.ops.JobLease lease;

    public FiscalResolutionJob(InvoiceService invoices, br.com.engenheiro.api.shared.ops.JobLease lease) {
        this.invoices = invoices;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${engenheiro.fiscal.resolve-interval:PT5M}",
            initialDelayString = "${engenheiro.fiscal.resolve-interval:PT5M}")
    public void run() {
        // Várias instâncias: só uma consulta o provedor por intervalo (F16).
        if (!lease.tryAcquire("fiscal-resolution", java.time.Duration.ofMinutes(4))) {
            return;
        }
        try {
            int n = invoices.resolvePending();
            if (n > 0) {
                log.info("Notas com resultado incerto resolvidas: {}", n);
            }
        } catch (RuntimeException e) {
            log.warn("Consulta de notas incertas falhou: {}", br.com.engenheiro.api.shared.ops.LogSanitizer.clean(e.getMessage()));
        }
    }
}
