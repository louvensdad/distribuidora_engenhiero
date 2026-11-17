package br.com.engenheiro.api.support;

import br.com.engenheiro.api.integrations.FiscalProvider;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Provedor fiscal FALSO, só no código de teste (não existe no produto). Desligado por padrão: os testes que não o
 * ligam veem o estado real NÃO CONFIGURADO. Simula um provedor idempotente pela referência: guarda o que "emitiu"
 * e responde consultas a partir disso — inclusive quando a resposta do envio se perdeu (TIMEOUT_AFTER_ISSUE).
 */
@Component
public class TestFiscalProvider implements FiscalProvider {
    public enum Mode { AUTHORIZE, REJECT, TIMEOUT_AFTER_ISSUE, TIMEOUT_NOT_RECEIVED, UNAVAILABLE }

    public static volatile boolean enabled = false;
    public static volatile Mode mode = Mode.AUTHORIZE;
    public static volatile String cancelMode = "CANCELLED";
    /** Notas que o "provedor" emitiu, por referência (nunca duas para a mesma). */
    public static final Map<String, Result> ISSUED = new ConcurrentHashMap<>();
    public static final AtomicInteger SUBMITS = new AtomicInteger();
    public static final AtomicInteger EMISSIONS = new AtomicInteger();
    private static final AtomicInteger NUMBER = new AtomicInteger(1000);
    /** Quando definido, segura cada transmissão até todas lerem a nota como conferida (corrida determinística). */
    public static volatile java.util.concurrent.CyclicBarrier gate;

    public static void reset() {
        enabled = false;
        mode = Mode.AUTHORIZE;
        cancelMode = "CANCELLED";
        ISSUED.clear();
        SUBMITS.set(0);
        EMISSIONS.set(0);
        gate = null;
    }

    @Override
    public String name() {
        return "fiscal-teste";
    }

    @Override
    public boolean configured() {
        return enabled;
    }

    @Override
    public boolean supports(String documentType) {
        java.util.concurrent.CyclicBarrier g = gate;
        if (g != null) {
            try {
                g.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return true;
    }

    @Override
    public Result submit(Payload p) {
        SUBMITS.incrementAndGet();
        switch (mode) {
            case UNAVAILABLE -> throw new Unavailable("conexão recusada (teste)");
            case TIMEOUT_NOT_RECEIVED -> throw new Timeout("sem resposta (teste)");
            case REJECT -> {
                return new Result("REJECTED", null, null, null, null, null, null, "778",
                        "Rejeição: NCM inexistente (teste)");
            }
            default -> {
            }
        }
        Result r = ISSUED.computeIfAbsent(p.reference(), ref -> {
            EMISSIONS.incrementAndGet();
            int n = NUMBER.incrementAndGet();
            String key = String.format("35%012d%030d", n, n).substring(0, 44);
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><nfeProc><NFe><infNFe Id=\"NFe" + key
                    + "\"><ide><nNF>" + n + "</nNF></ide><total>" + p.total().toPlainString()
                    + "</total></infNFe></NFe><protNFe><nProt>1" + n + "</nProt></protNFe></nfeProc>";
            return new Result("AUTHORIZED", "ext-" + n, (long) n, p.series(), key, "1" + n, xml.getBytes(StandardCharsets.UTF_8),
                    null, null);
        });
        if (mode == Mode.TIMEOUT_AFTER_ISSUE) {
            throw new Timeout("emitiu, mas a resposta se perdeu (teste)");
        }
        return r;
    }

    @Override
    public Result query(String reference) {
        Result r = ISSUED.get(reference);
        return r == null ? new Result("NOT_FOUND", null, null, null, null, null, null, null, null) : r;
    }

    @Override
    public CancelResult cancel(String reference, String accessKey, String reason) {
        return switch (cancelMode) {
            case "TIMEOUT" -> throw new Timeout("sem resposta ao cancelar (teste)");
            case "REJECTED" -> new CancelResult("REJECTED", null, "Prazo de cancelamento expirado (teste)");
            default -> new CancelResult("CANCELLED", "9" + accessKey.substring(30), null);
        };
    }
}
