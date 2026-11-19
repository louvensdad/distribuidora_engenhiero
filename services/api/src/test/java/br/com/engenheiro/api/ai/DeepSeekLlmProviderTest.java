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

/** Adaptador DeepSeek (ADR-032) contra servidor HTTP local que imita /chat/completions. Nada sai da máquina. */
class DeepSeekLlmProviderTest {
    HttpServer server;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    final List<String> replies = new CopyOnWriteArrayList<>();
    final JsonMapper json = JsonMapper.builder().build();
    int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> {
            requests.add(json.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (status == 200 ? replies.remove(0)
                    : "{\"error\":{\"message\":\"x\",\"type\":\"authentication_error\"}}").getBytes(StandardCharsets.UTF_8);
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

    DeepSeekLlmProvider provider(String key, String selected) {
        return new DeepSeekLlmProvider(key, "deepseek-flash", selected, "http://127.0.0.1:" + server.getAddress().getPort(), 4096);
    }

    static final LlmProvider.ToolSpec RESUMO = new LlmProvider.ToolSpec("consultar_resumo", "Resumo do período",
            Map.of("type", "object", "properties", Map.of("de", Map.of("type", "string")), "required", List.of("de"),
                    "additionalProperties", false));

    @Test
    void semChaveOuComOutroProvedorNaoConfiguradoENadaSai() {
        assertThat(provider("", "deepseek").configured()).isFalse();
        assertThat(provider("sk-teste", "anthropic").configured()).isFalse();
        assertThat(provider("sk-teste", "deepseek").configured()).isTrue();
        assertThat(requests).isEmpty();
    }

    @Test
    void lacoDeFerramentasNoFioCompativelComOpenAi() {
        replies.add("""
                {"model":"deepseek-flash","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
                 "tool_calls":[{"id":"call_1","type":"function","function":{"name":"consultar_resumo","arguments":"{\\"de\\":\\"2026-11-01\\"}"}}]}}],
                 "usage":{"prompt_tokens":1000,"completion_tokens":40,"prompt_cache_hit_tokens":600}}""");
        replies.add("""
                {"model":"deepseek-flash","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Lucro de R$ 9.500,00."}}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":30,"prompt_cache_hit_tokens":1000}}""");
        LlmProvider.Conversation c = provider("sk-teste", "deepseek").start("sistema", List.of(RESUMO), "Qual o lucro?");
        LlmProvider.Turn t1 = c.next();
        assertThat(t1.stopReason()).isEqualTo("TOOL_USE");
        assertThat(t1.toolCalls()).singleElement().satisfies(tc -> {
            assertThat(tc.id()).isEqualTo("call_1");
            assertThat(tc.name()).isEqualTo("consultar_resumo");
            assertThat(tc.input()).containsEntry("de", "2026-11-01");
        });
        assertThat(t1.inputTokens()).isEqualTo(400);     // 1000 − 600 lidos do cache (cobrados à parte)
        assertThat(t1.cacheReadTokens()).isEqualTo(600);
        assertThat(t1.outputTokens()).isEqualTo(40);
        c.addToolResults(List.of(new LlmProvider.ToolResult("call_1", "{\"lucro\":\"9500.00\"}", false)));
        LlmProvider.Turn t2 = c.next();
        assertThat(t2.stopReason()).isEqualTo("END");
        assertThat(t2.text()).isEqualTo("Lucro de R$ 9.500,00.");
        assertThat(t2.model()).isEqualTo("deepseek-flash");

        JsonNode first = requests.get(0);
        assertThat(auth).containsOnly("Bearer sk-teste");
        assertThat(first.path("model").asString()).isEqualTo("deepseek-flash");
        assertThat(first.path("thinking").path("type").asString()).isEqualTo("disabled");
        assertThat(first.path("messages").get(0).path("role").asString()).isEqualTo("system");
        JsonNode tool = first.path("tools").get(0);
        assertThat(tool.path("type").asString()).isEqualTo("function");
        assertThat(tool.path("function").path("name").asString()).isEqualTo("consultar_resumo");
        assertThat(tool.path("function").path("parameters").path("additionalProperties").asBoolean(true)).isFalse();
        // Segunda chamada: histórico com o turno do assistente (pedido de ferramenta) e o resultado role=tool.
        JsonNode msgs = requests.get(1).path("messages");
        assertThat(msgs.size()).isEqualTo(4);
        assertThat(msgs.get(2).path("tool_calls").get(0).path("id").asString()).isEqualTo("call_1");
        assertThat(msgs.get(3).path("role").asString()).isEqualTo("tool");
        assertThat(msgs.get(3).path("tool_call_id").asString()).isEqualTo("call_1");
        assertThat(msgs.get(3).path("content").asString()).contains("9500.00");
    }

    @Test
    void erroDoProvedorViraIndisponivelSemExporAChave() {
        status = 401;
        LlmProvider.Conversation c = provider("sk-segredo", "deepseek").start("s", List.of(), "p");
        assertThatThrownBy(c::next).isInstanceOf(LlmProvider.Unavailable.class)
                .hasMessageContaining("401").hasMessageContaining("authentication_error").hasMessageNotContaining("sk-segredo");
    }
}
