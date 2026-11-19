package br.com.engenheiro.api.ai;

import java.util.List;
import java.util.Map;

/**
 * Porta do provedor de modelo de linguagem (F15, D13). Só o servidor fala com o provedor; a chave fica em variável de
 * ambiente/cofre e nunca sai daqui. Sem chave, {@link #configured()} é falso e a IA aparece NÃO CONFIGURADA — nunca
 * uma resposta simulada.
 *
 * A conversa é conduzida pelo {@link AiService} (laço manual): o provedor devolve texto e/ou pedidos de ferramenta; o
 * serviço executa só as ferramentas somente leitura do catálogo, com a identidade do usuário, e devolve os resultados.
 */
public interface LlmProvider {
    /** Nome do provedor, ex.: "anthropic". */
    String name();

    /** Modelo configurado (versão gravada em cada pergunta). */
    String model();

    boolean configured();

    Conversation start(String system, List<ToolSpec> tools, String userMessage);

    /** Ferramenta oferecida ao modelo: nome, descrição e JSON Schema de entrada (sem organização/usuário). */
    record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
    }

    record ToolCall(String id, String name, Map<String, Object> input) {
    }

    record ToolResult(String toolCallId, String content, boolean error) {
    }

    /**
     * Um turno do modelo. stopReason: END (respondeu), TOOL_USE (pediu ferramentas), MAX_TOKENS, REFUSAL ou OTHER.
     * model é o identificador devolvido pelo provedor (versão efetiva).
     */
    record Turn(String text, List<ToolCall> toolCalls, String stopReason, String model, long inputTokens,
                long outputTokens, long cacheReadTokens) {
    }

    interface Conversation {
        Turn next();

        void addToolResults(List<ToolResult> results);
    }

    /** Falha do provedor (rede, limite, indisponível). Nada foi alterado no sistema. */
    class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
