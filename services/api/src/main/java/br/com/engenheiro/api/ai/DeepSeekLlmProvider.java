package br.com.engenheiro.api.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador DeepSeek da porta {@link LlmProvider} — escolha do responsável em 19/11/2026 (ADR-032, revê a D13).
 * API compatível com OpenAI ({@code POST /chat/completions}, ferramentas {@code type: function}, resultado de
 * ferramenta como mensagem {@code role: tool}). Modelo padrão {@code deepseek-flash}, sem modo de raciocínio (mais
 * barato e direto para consultas com ferramentas). A chave vem só de {@code DEEPSEEK_API_KEY} no servidor.
 *
 * <p>Ativo apenas com {@code ENGENHEIRO_IA_PROVEDOR=deepseek} e chave presente; sem isso a IA fica NÃO CONFIGURADA e
 * nada é enviado. Falha de rede ou resposta de erro vira {@link Unavailable} (nenhuma alteração no sistema).
 */
@Component
public class DeepSeekLlmProvider implements LlmProvider {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String apiKey;
    private final String model;
    private final boolean active;
    private final String baseUrl;
    private final int maxTokens;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public DeepSeekLlmProvider(@Value("${engenheiro.ia.deepseek.api-key:}") String apiKey,
                               @Value("${engenheiro.ia.deepseek.modelo:deepseek-flash}") String model,
                               @Value("${engenheiro.ia.provedor:anthropic}") String selected,
                               @Value("${engenheiro.ia.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                               @Value("${engenheiro.ia.deepseek.max-tokens:4096}") int maxTokens) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.active = "deepseek".equals(selected);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.maxTokens = maxTokens;
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean configured() {
        return active && !apiKey.isEmpty();
    }

    @Override
    public Conversation start(String system, List<ToolSpec> tools, String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", userMessage));
        List<Map<String, Object>> toolDefs = new ArrayList<>();
        for (ToolSpec t : tools) {
            toolDefs.add(Map.of("type", "function", "function",
                    Map.of("name", t.name(), "description", t.description(), "parameters", t.inputSchema())));
        }
        return new Conversation() {
            @Override
            public Turn next() {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("messages", messages);
                if (!toolDefs.isEmpty()) {
                    body.put("tools", toolDefs);
                }
                body.put("thinking", Map.of("type", "disabled"));
                body.put("max_tokens", maxTokens);
                body.put("stream", false);
                JsonNode r = post(body);
                JsonNode choice = r.path("choices").path(0);
                JsonNode msg = choice.path("message");
                String text = msg.path("content").isNull() ? "" : msg.path("content").asString("");
                List<ToolCall> calls = new ArrayList<>();
                List<Map<String, Object>> echo = new ArrayList<>();
                for (JsonNode c : msg.path("tool_calls")) {
                    String id = c.path("id").asString();
                    String name = c.path("function").path("name").asString();
                    String args = c.path("function").path("arguments").asString("{}");
                    Map<String, Object> input;
                    try {
                        input = JSON.readValue(args.isBlank() ? "{}" : args, Map.class);
                    } catch (RuntimeException e) {
                        input = Map.of(); // argumentos inválidos: a ferramenta recebe vazio e o serviço valida
                    }
                    calls.add(new ToolCall(id, name, input));
                    echo.add(Map.of("id", id, "type", "function", "function", Map.of("name", name, "arguments", args)));
                }
                // O turno do assistente volta intacto ao histórico (com os pedidos de ferramenta).
                Map<String, Object> assistant = new LinkedHashMap<>();
                assistant.put("role", "assistant");
                assistant.put("content", text);
                if (!echo.isEmpty()) {
                    assistant.put("tool_calls", echo);
                }
                messages.add(assistant);
                String stop = switch (choice.path("finish_reason").asString("")) {
                    case "stop" -> "END";
                    case "tool_calls" -> "TOOL_USE";
                    case "length" -> "MAX_TOKENS";
                    case "content_filter" -> "REFUSAL";
                    default -> calls.isEmpty() ? "OTHER" : "TOOL_USE";
                };
                JsonNode u = r.path("usage");
                long cacheHit = u.path("prompt_cache_hit_tokens").asLong(0);
                long prompt = u.path("prompt_tokens").asLong(0);
                return new Turn(text, calls, stop, r.path("model").asString(model), Math.max(0, prompt - cacheHit),
                        u.path("completion_tokens").asLong(0), cacheHit);
            }

            @Override
            public void addToolResults(List<ToolResult> results) {
                for (ToolResult t : results) {
                    messages.add(Map.of("role", "tool", "tool_call_id", t.toolCallId(),
                            "content", t.error() ? "ERRO: " + t.content() : t.content()));
                }
            }
        };
    }

    private JsonNode post(Map<String, Object> body) {
        HttpResponse<String> r;
        try {
            r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("interrompido", e);
        } catch (java.io.IOException e) {
            throw new Unavailable("provedor de IA inacessível (" + e.getClass().getSimpleName() + ")", e);
        }
        if (r.statusCode() != 200) {
            // Só o código e o tipo do erro (nunca a chave nem o conteúdo da pergunta).
            String type = "";
            try {
                type = JSON.readTree(r.body()).path("error").path("type").asString("");
            } catch (RuntimeException ignored) {
                // corpo não-JSON
            }
            throw new Unavailable("provedor de IA respondeu " + r.statusCode() + (type.isEmpty() ? "" : " (" + type + ")"), null);
        }
        return JSON.readTree(r.body());
    }
}
