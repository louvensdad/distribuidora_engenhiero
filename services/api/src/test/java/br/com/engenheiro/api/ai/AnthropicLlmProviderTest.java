package br.com.engenheiro.api.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador Claude (SDK oficial) contra um servidor HTTP local que imita a API de mensagens: confere o que sai no fio
 * (modelo, ferramentas estritas, pensamento adaptativo, esforço, contingência, resultados numa única mensagem) e a
 * leitura de pedidos de ferramenta, uso de tokens e modelo efetivo. Nenhuma chamada sai da máquina.
 */
class AnthropicLlmProviderTest {
    HttpServer server;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> headers = new CopyOnWriteArrayList<>();
    final JsonMapper json = JsonMapper.builder().build();
    final List<String> replies = new CopyOnWriteArrayList<>();
    int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            requests.add(json.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            headers.add(ex.getRequestHeaders().getFirst("Authorization") + "|" + ex.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] body = (status == 200 ? replies.remove(0)
                    : "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"x\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    AnthropicLlmProvider provider(String key) {
        return new AnthropicLlmProvider(key, "claude-opus-5-5", "medium", "anthropic",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    static String usage(int in, int out) {
        return "\"usage\":{\"input_tokens\":" + in + ",\"output_tokens\":" + out + ",\"cache_read_input_tokens\":7}";
    }

    @Test
    void semChaveNaoConfiguradoENadaSai() {
        AnthropicLlmProvider p = provider("  ");
        assertThat(p.configured()).isFalse();
        assertThatThrownBy(() -> p.start("s", List.of(), "q")).isInstanceOf(IllegalStateException.class);
        assertThat(new AnthropicLlmProvider("k", "m", "medium", "teste", "").configured()).isFalse(); // outro provedor
        assertThat(requests).isEmpty();
    }

    @Test
    void lacoDeFerramentasNoFio() {
        replies.add("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                + "\"content\":[{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"consultar_resumo\",\"input\":{\"inicio\":\"2026-11-01\"}},"
                + "{\"type\":\"tool_use\",\"id\":\"tu_2\",\"name\":\"consultar_meta\",\"input\":{}}],"
                + "\"stop_reason\":\"tool_use\",\"stop_sequence\":null," + usage(100, 20) + "}");
        replies.add("{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-4-8\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Lucro de R$ 10,00.\"}],"
                + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null," + usage(300, 40) + "}");
        AnthropicLlmProvider p = provider("chave-ficticia-de-teste");
        LlmProvider.ToolSpec spec = new LlmProvider.ToolSpec("consultar_resumo", "Resumo", Map.of("type", "object",
                "properties", Map.of("inicio", Map.of("type", "string")), "required", List.of()));
        LlmProvider.Conversation c = p.start("regras", List.of(spec), "pergunta");

        LlmProvider.Turn t1 = c.next();
        assertThat(t1.stopReason()).isEqualTo("TOOL_USE");
        assertThat(t1.toolCalls()).extracting(LlmProvider.ToolCall::name).containsExactly("consultar_resumo", "consultar_meta");
        assertThat(t1.toolCalls().get(0).input()).containsEntry("inicio", "2026-11-01");
        assertThat(t1.inputTokens()).isEqualTo(100);
        assertThat(t1.cacheReadTokens()).isEqualTo(7);
        c.addToolResults(List.of(new LlmProvider.ToolResult("tu_1", "{\"lucro\":\"10.00\"}", false),
                new LlmProvider.ToolResult("tu_2", "{\"erro\":\"SEM_PERMISSAO\"}", true)));
        LlmProvider.Turn t2 = c.next();
        assertThat(t2.stopReason()).isEqualTo("END");
        assertThat(t2.text()).isEqualTo("Lucro de R$ 10,00.");
        assertThat(t2.model()).isEqualTo("claude-opus-4-8"); // modelo efetivo (contingência) é o gravado

        JsonNode r1 = requests.get(0);
        assertThat(r1.path("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(r1.path("thinking").path("type").asString()).isEqualTo("adaptive");
        assertThat(r1.path("output_config").path("effort").asString()).isEqualTo("medium");
        assertThat(r1.path("fallbacks").asString()).isEqualTo("default");
        assertThat(r1.path("system").asString()).isEqualTo("regras");
        JsonNode tool = r1.path("tools").get(0);
        assertThat(tool.path("strict").asBoolean()).isTrue();
        assertThat(tool.path("input_schema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(headers.get(0)).isEqualTo("Bearer chave-ficticia-de-teste|server-side-fallback-2026-07-01");

        // Segunda chamada: histórico inteiro (pergunta, turno do assistente intacto) e os dois resultados numa mensagem.
        JsonNode msgs = requests.get(1).path("messages");
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(1).path("role").asString()).isEqualTo("assistant");
        assertThat(msgs.get(1).path("content")).hasSize(2);
        JsonNode results = msgs.get(2).path("content");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).path("type").asString()).isEqualTo("tool_result");
        assertThat(results.get(1).path("is_error").asBoolean()).isTrue();
    }

    @Test
    void erroDoProvedorViraIndisponivel() {
        status = 400;
        LlmProvider.Conversation c = provider("chave-ficticia-de-teste").start("s", List.of(), "q");
        assertThatThrownBy(c::next).isInstanceOf(LlmProvider.Unavailable.class);
    }
}
