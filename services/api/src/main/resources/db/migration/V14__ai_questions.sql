-- F15: IA da gestão. Cada pergunta registra quem perguntou, por qual app, as ferramentas (somente leitura) usadas,
-- as fontes autorizadas, modelo/versão, tokens, custo e latência. Nunca guarda chave, token ou segredo.
-- reserved_usd: reserva feita antes de chamar o provedor (o orçamento conta reservas em curso para não estourar com
-- perguntas simultâneas); ao terminar, a reserva zera e cost_usd recebe o custo real.
CREATE TABLE ai_questions (
    id                 uuid          PRIMARY KEY,
    organization_id    uuid          NOT NULL REFERENCES organizations (id),
    user_id            uuid          NOT NULL REFERENCES app_users (id),
    client_id          text          NOT NULL,
    question           text          NOT NULL CHECK (length(question) BETWEEN 1 AND 2000),
    period_from        date          NOT NULL,
    period_to          date          NOT NULL CHECK (period_to > period_from),
    state              text          NOT NULL CHECK (state IN ('RUNNING', 'ANSWERED', 'FAILED', 'REFUSED', 'LIMIT_REACHED')),
    answer             text,
    limitations        jsonb         NOT NULL DEFAULT '[]'::jsonb,
    tools              jsonb         NOT NULL DEFAULT '[]'::jsonb,
    sources            jsonb         NOT NULL DEFAULT '[]'::jsonb,
    provider           text          NOT NULL,
    model              text,
    input_tokens       bigint        NOT NULL DEFAULT 0,
    output_tokens      bigint        NOT NULL DEFAULT 0,
    cache_read_tokens  bigint        NOT NULL DEFAULT 0,
    cost_usd           numeric(12,6) NOT NULL DEFAULT 0 CHECK (cost_usd >= 0),
    reserved_usd       numeric(12,6) NOT NULL DEFAULT 0 CHECK (reserved_usd >= 0),
    latency_ms         integer,
    error_code         text,
    cutoff_at          timestamptz   NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    finished_at        timestamptz
);

CREATE INDEX ai_questions_org_month ON ai_questions (organization_id, created_at);
CREATE INDEX ai_questions_user ON ai_questions (organization_id, user_id, created_at DESC);
