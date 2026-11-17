-- F09: metas semanais/mensais (REP-07). O realizado não é gravado: vem sempre da mesma agregação do relatório.
-- Rascunho é editável; ativa é imutável (mudar o alvo = cancelar com motivo e criar outra), com histórico.
CREATE TABLE goals (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          REFERENCES branches (id),             -- null = empresa toda
    metric          text          NOT NULL CHECK (metric IN ('NET_REVENUE', 'SOLD_QUANTITY', 'PROFIT')),
    unit            text          CHECK (unit ~ '^[A-Z_]{2,20}$'),      -- unidade-base, só para SOLD_QUANTITY
    period_kind     text          NOT NULL CHECK (period_kind IN ('WEEK', 'MONTH')),
    period_start    date          NOT NULL,
    target          numeric(19,6) NOT NULL CHECK (target > 0),
    title           text          NOT NULL CHECK (length(title) BETWEEN 3 AND 120),
    notes           text          CHECK (length(notes) <= 500),
    state           text          NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'ACTIVE', 'CANCELLED')),
    cancel_reason   text          CHECK (length(cancel_reason) <= 500),
    version         bigint        NOT NULL DEFAULT 1,
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    activated_by    uuid          REFERENCES app_users (id),
    activated_at    timestamptz,
    cancelled_by    uuid          REFERENCES app_users (id),
    cancelled_at    timestamptz,
    -- semana começa na segunda-feira; mês no dia 1
    CHECK (period_kind <> 'WEEK' OR extract(isodow FROM period_start) = 1),
    CHECK (period_kind <> 'MONTH' OR extract(day FROM period_start) = 1),
    CHECK ((metric = 'SOLD_QUANTITY') = (unit IS NOT NULL)),
    CHECK (metric = 'SOLD_QUANTITY' OR target = round(target, 2)),
    CHECK (state <> 'ACTIVE' OR activated_by IS NOT NULL),
    CHECK (state <> 'CANCELLED' OR (cancelled_by IS NOT NULL AND cancel_reason IS NOT NULL))
);
-- Uma meta ativa por indicador/período/unidade (de negócio e de medida).
CREATE UNIQUE INDEX ux_goals_active ON goals (organization_id, coalesce(branch_id, '00000000-0000-0000-0000-000000000000'),
    metric, coalesce(unit, ''), period_kind, period_start) WHERE state = 'ACTIVE';
CREATE INDEX ix_goals_period ON goals (organization_id, period_start DESC);
