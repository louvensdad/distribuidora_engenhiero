-- Pedido do responsável (ADR-021): calendário de folgas e férias por funcionário (escala = quem está disponível em
-- cada dia e setor) e Open Finance agnóstico de agregador (consentimento, extrato importado, conciliação e despesa
-- sugerida com aprovação). Sem agregador contratado: NÃO CONFIGURADO (nada simulado).

-- Ausências: férias, folga, afastamento. Uma pessoa não tem duas ausências aprovadas sobrepostas.
CREATE TABLE employee_absences (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    branch_id       uuid        NOT NULL REFERENCES branches (id),
    membership_id   uuid        NOT NULL REFERENCES memberships (id),
    kind            text        NOT NULL CHECK (kind IN ('VACATION', 'DAY_OFF', 'LEAVE')),
    start_date      date        NOT NULL,
    end_date        date        NOT NULL,
    state           text        NOT NULL DEFAULT 'REQUESTED' CHECK (state IN ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    notes           text CHECK (length(notes) <= 500),
    decided_by      uuid        REFERENCES app_users (id),
    decided_at      timestamptz,
    decided_note    text CHECK (length(decided_note) <= 500),
    version         bigint      NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid        NOT NULL REFERENCES app_users (id),
    CHECK (end_date >= start_date),
    CHECK (end_date - start_date < 60),
    CHECK (state IN ('REQUESTED', 'CANCELLED') OR decided_by IS NOT NULL)
);
CREATE INDEX ix_absences_org_dates ON employee_absences (organization_id, start_date, end_date);
CREATE INDEX ix_absences_member ON employee_absences (membership_id, start_date);

-- Open Finance: conexão (consentimento) por conta bancária da empresa.
CREATE TABLE bank_connections (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations (id),
    fin_account_id    uuid        NOT NULL REFERENCES fin_accounts (id),
    provider          text        NOT NULL CHECK (length(provider) BETWEEN 1 AND 40),
    institution       text        NOT NULL CHECK (length(institution) BETWEEN 1 AND 120),
    company_document  text        NOT NULL CHECK (company_document ~ '^[0-9]{14}$'),
    external_id       text,                         -- id do consentimento/item no agregador
    state             text        NOT NULL CHECK (state IN ('PENDING_CONSENT', 'ACTIVE', 'REVOKED', 'EXPIRED', 'ERROR')),
    consent_url       text,
    last_sync_at      timestamptz,
    last_error        text CHECK (length(last_error) <= 500),
    provider_balance  numeric(19,2),
    provider_balance_at timestamptz,
    version           bigint      NOT NULL DEFAULT 1,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid        NOT NULL REFERENCES app_users (id)
);
CREATE UNIQUE INDEX ux_bank_connections_active ON bank_connections (fin_account_id) WHERE state IN ('PENDING_CONSENT', 'ACTIVE');

-- Lançamentos do extrato importados (uma vez por transação do banco).
CREATE TABLE bank_transactions (
    id               uuid PRIMARY KEY,
    organization_id  uuid          NOT NULL REFERENCES organizations (id),
    connection_id    uuid          NOT NULL REFERENCES bank_connections (id),
    fin_account_id   uuid          NOT NULL REFERENCES fin_accounts (id),
    provider_tx_id   text          NOT NULL CHECK (length(provider_tx_id) BETWEEN 1 AND 120),
    booked_on        date          NOT NULL,
    amount           numeric(19,2) NOT NULL CHECK (amount > 0),
    direction        text          NOT NULL CHECK (direction IN ('IN', 'OUT')),
    description      text          NOT NULL CHECK (length(description) <= 300),
    counterpart      text CHECK (length(counterpart) <= 120),
    state            text          NOT NULL CHECK (state IN ('MATCHED', 'SUGGESTED', 'UNMATCHED', 'IGNORED')),
    matched_type     text,
    matched_id       uuid,
    suggested_expense_id uuid      REFERENCES expenses (id),
    suggested_category text,
    decided_by       uuid          REFERENCES app_users (id),
    decided_note     text CHECK (length(decided_note) <= 500),
    imported_at      timestamptz   NOT NULL DEFAULT now(),
    version          bigint        NOT NULL DEFAULT 1,
    UNIQUE (fin_account_id, provider_tx_id)
);
CREATE INDEX ix_bank_tx_org ON bank_transactions (organization_id, state, booked_on DESC);
-- Um lançamento do sistema só concilia com uma transação do banco.
CREATE UNIQUE INDEX ux_bank_tx_matched ON bank_transactions (matched_type, matched_id) WHERE matched_id IS NOT NULL;

-- Despesa sugerida a partir do extrato: aprovação já registra o pagamento pela conta do banco (o dinheiro já saiu).
ALTER TABLE expenses ADD COLUMN bank_transaction_id uuid REFERENCES bank_transactions (id);
CREATE UNIQUE INDEX ux_expenses_bank_tx ON expenses (bank_transaction_id) WHERE bank_transaction_id IS NOT NULL;
