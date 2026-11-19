package br.com.engenheiro.api.support;

import br.com.engenheiro.api.ai.LlmProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Provedor de IA FALSO, só no código de teste (não existe no produto). Desligado por padrão: quem não o liga vê o
 * estado real NÃO CONFIGURADO. Um "cérebro" roteirizado decide cada turno a partir do que o servidor já devolveu,
 * e cada troca fica registrada para o teste conferir o que o modelo recebeu (ferramentas, dados, regras).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TestLlmProvider implements LlmProvider {
    public static volatile boolean enabled = false;
    public static volatile Function<Exchange, Turn> brain = x -> answer("ok");
    public static final List<Exchange> LOG = new CopyOnWriteArrayList<>();
    /** Quando definido, o primeiro turno espera este sinal (pergunta em curso, para testar concorrência). */
    public static volatile CountDownLatch hold;
    public static volatile CountDownLatch started;

    public static void reset() {
        enabled = false;
        brain = x -> answer("ok");
        LOG.clear();
        hold = null;
        started = null;
    }

    /** O que o modelo recebeu até aqui: regras, catálogo, pergunta e os resultados de cada rodada de ferramentas. */
    public record Exchange(String system, List<String> tools, String user, List<List<ToolResult>> results) {
        public int round() {
            return results.size();
        }

        public List<ToolResult> last() {
            return results.isEmpty() ? List.of() : results.get(results.size() - 1);
        }
    }

    public static Turn answer(String text) {
        return new Turn(text, List.of(), "END", "modelo-de-teste-1", 1200, 300, 0);
    }

    public static Turn tool(String name, Map<String, Object> input) {
        return new Turn("", List.of(new ToolCall("call-" + name + "-" + System.nanoTime(), name, input)), "TOOL_USE",
                "modelo-de-teste-1", 1000, 50, 200);
    }

    public static Turn tools(List<ToolCall> calls) {
        return new Turn("", calls, "TOOL_USE", "modelo-de-teste-1", 1000, 50, 0);
    }

    @Override
    public String name() {
        return "teste";
    }

    @Override
    public String model() {
        return "modelo-de-teste";
    }

    @Override
    public boolean configured() {
        return enabled;
    }

    @Override
    public Conversation start(String system, List<ToolSpec> tools, String userMessage) {
        Exchange x = new Exchange(system, tools.stream().map(ToolSpec::name).toList(), userMessage, new ArrayList<>());
        LOG.add(x);
        return new Conversation() {
            @Override
            public Turn next() {
                if (x.round() == 0 && hold != null) {
                    if (started != null) {
                        started.countDown();
                    }
                    try {
                        hold.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return brain.apply(x);
            }

            @Override
            public void addToolResults(List<ToolResult> results) {
                x.results().add(List.copyOf(results));
            }
        };
    }
}
