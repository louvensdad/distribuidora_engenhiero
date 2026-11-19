package br.com.engenheiro.api.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adaptador Claude (Anthropic) da porta {@link LlmProvider} — escolha registrada no ADR-029 (D13). A chave vem só de
 * {@code ANTHROPIC_API_KEY} no servidor; sem ela o adaptador fica NÃO CONFIGURADO e nenhuma chamada sai daqui.
 * Usa o SDK oficial em Java, laço manual de ferramentas, pensamento adaptativo e esforço configurável.
 */
@Component
public class AnthropicLlmProvider implements LlmProvider {
    private final String apiKey;
    private final String model;
    private final String effort;
    private final boolean active;
    private final String baseUrl;
    private volatile AnthropicClient client;

    public AnthropicLlmProvider(@Value("${engenheiro.ia.anthropic.api-key:}") String apiKey,
                                @Value("${engenheiro.ia.modelo:claude-opus-5-5}") String model,
                                @Value("${engenheiro.ia.esforco:medium}") String effort,
                                @Value("${engenheiro.ia.provedor:anthropic}") String selected,
                                @Value("${engenheiro.ia.anthropic.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.effort = effort;
        this.active = "anthropic".equals(selected);
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean configured() {
        return active && !apiKey.isEmpty();
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    AnthropicOkHttpClient.Builder cb = AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(2)
                            .timeout(Duration.ofSeconds(180));
                    if (!baseUrl.isEmpty()) {
                        cb.baseUrl(baseUrl); // proxy corporativo ou servidor de teste local
                    }
                    client = cb.build();
                }
                c = client;
            }
        }
        return c;
    }

    @Override
    public Conversation start(String system, List<ToolSpec> tools, String userMessage) {
        if (!configured()) {
            throw new IllegalStateException("Provedor de IA não configurado");
        }
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build())
                .system(system)
                // Recusa por classificador: o próprio provedor tenta o modelo de contingência ("default"); o modelo
                // efetivo volta em Message.model() e fica gravado na pergunta.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        for (ToolSpec t : tools) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) t.inputSchema().getOrDefault("properties", Map.of());
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) t.inputSchema().getOrDefault("required", List.of());
            Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
            props.forEach((k, v) -> pb.putAdditionalProperty(k, JsonValue.from(v)));
            b.addTool(Tool.builder().name(t.name()).description(t.description())
                    .inputSchema(Tool.InputSchema.builder().properties(pb.build()).required(required)
                            .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
                    .strict(true)
                    .build());
        }
        b.addUserMessage(userMessage);
        return new Conversation() {
            @Override
            public Turn next() {
                Message m;
                try {
                    m = client().messages().create(b.build());
                } catch (AnthropicException e) {
                    throw new Unavailable("Provedor de IA indisponível: " + e.getClass().getSimpleName(), e);
                }
                b.addMessage(m); // devolve o turno inteiro (inclusive blocos de pensamento) sem alterar
                StringBuilder text = new StringBuilder();
                List<ToolCall> calls = new ArrayList<>();
                for (ContentBlock block : m.content()) {
                    block.text().ifPresent(t -> text.append(t.text()));
                    block.toolUse().ifPresent(u -> calls.add(new ToolCall(u.id(), u.name(),
                            u._input().convert(new TypeReference<Map<String, Object>>() {
                            }))));
                }
                StopReason sr = m.stopReason().orElse(null);
                String stop = sr == null ? "OTHER" : sr.equals(StopReason.END_TURN) ? "END"
                        : sr.equals(StopReason.TOOL_USE) ? "TOOL_USE" : sr.equals(StopReason.MAX_TOKENS) ? "MAX_TOKENS"
                        : sr.equals(StopReason.REFUSAL) ? "REFUSAL" : "OTHER";
                return new Turn(text.toString(), calls, stop, m.model().asString(), m.usage().inputTokens(),
                        m.usage().outputTokens(), m.usage().cacheReadInputTokens().orElse(0L));
            }

            @Override
            public void addToolResults(List<ToolResult> results) {
                List<ContentBlockParam> blocks = new ArrayList<>();
                for (ToolResult r : results) {
                    blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(r.toolCallId())
                            .content(r.content()).isError(r.error()).build()));
                }
                b.addUserMessageOfContentBlockParams(blocks); // todos os resultados numa única mensagem
            }
        };
    }
}
