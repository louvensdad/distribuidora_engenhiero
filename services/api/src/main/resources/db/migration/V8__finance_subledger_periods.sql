-- F06: subledger financeiro gerencial — contas (caixa/banco/adquirente), competência, despesas por categoria,
-- liquidação de contas a pagar, contas a receber (clientes, cartões em parcelas, créditos de fornecedor),
-- transferências internas, períodos com fechamento/reabertura e trava no livro.

-- Contas financeiras. Cada conta do livro (CAIXA, BANCO, RECEBIVEL_CARTAO) tem uma conta padrão por organização,
-- que recebe as linhas antigas/sem conta explícita (o histórico não é reescrito).
CREATE TABLE fin_accounts (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    code            text        NOT NULL CHECK (code ~ '^[A-Z0-9_]{2,40}$'),
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    ledger_account  text        NOT NULL CHECK (ledger_account IN ('CAIXA', 'BANCO', 'RECEBIVEL_CARTAO')),
    kind            text        NOT NULL CHECK (kind IN ('CASH_DRAWERS', 'COMPANY_CASH', 'BANK', 'CARD_ACQUIRER')),
    is_default      boolean     NOT NULL DEFAULT false,
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version         bigint      NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid        REFERENCES app_users (id),
    UNIQUE (organization_id, code)
);
CREATE UNIQUE INDEX ux_fin_accounts_default ON fin_accounts (organization_id, ledger_account) WHERE is_default;

ALTER TABLE journal_lines ADD COLUMN fin_account_id uuid REFERENCES fin_accounts (id);
CREATE INDEX ix_journal_lines_account ON journal_lines (account, fin_account_id);
-- Competência (data civil) separada da data do lançamento; nula = mesma data do lançamento.
ALTER TABLE journal_entries ADD COLUMN competence date;
CREATE UNIQUE INDEX ux_journal_single_reversal ON journal_entries (reversal_of) WHERE reversal_of IS NOT NULL;

ALTER TABLE journal_lines DROP CONSTRAINT journal_lines_account_check;
ALTER TABLE journal_lines ADD CONSTRAINT journal_lines_account_check CHECK (account IN ('CAIXA', 'BANCO',
    'RECEBIVEL_CARTAO', 'CLIENTES', 'ESTOQUE', 'RECEITA_VENDAS', 'DESCONTOS_CONCEDIDOS', 'DEVOLUCOES_VENDAS', 'CMV',
    'TAXAS_CARTAO', 'FORNECEDORES', 'PERDAS_ESTOQUE', 'AJUSTES_ESTOQUE', 'DESPESAS_VEICULOS',
    'ADIANTAMENTOS_FUNCIONARIOS', 'DESPESAS_FUNCIONARIOS', 'DESPESAS_ESTRUTURA', 'DESPESAS_IMPOSTOS',
    'DESPESAS_OUTRAS', 'RETIRADAS_SOCIOS', 'SALDOS_INICIAIS', 'DIFERENCAS_CAIXA'));

-- Despesas por categoria (FIN-01). Reconhecida na aprovação, pela competência; paga pela conta a pagar.
CREATE TABLE expenses (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    category        text          NOT NULL CHECK (category IN ('FUNCIONARIOS', 'VEICULOS', 'ESTRUTURA', 'IMPOSTOS_TAXAS',
                                                               'OUTRAS', 'RETIRADA_SOCIO')),
    subcategory     text          NOT NULL CHECK (length(subcategory) BETWEEN 1 AND 60),
    description     text          NOT NULL CHECK (length(description) BETWEEN 1 AND 300),
    payee           text CHECK (length(payee) <= 120),
    supplier_id     uuid          REFERENCES suppliers (id),
    amount          numeric(19,2) NOT NULL CHECK (amount > 0),
    competence      date          NOT NULL,
    due_date        date,
    state           text          NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'REVERSED')),
    payable_id      uuid          REFERENCES payables (id),
    decided_by      uuid          REFERENCES app_users (id),
    decided_at      timestamptz,
    decided_note    text CHECK (length(decided_note) <= 500),
    reversed_by     uuid          REFERENCES app_users (id),
    reversed_at     timestamptz,
    reverse_reason  text CHECK (length(reverse_reason) <= 500),
    version         bigint        NOT NULL DEFAULT 1,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    CHECK (state = 'PENDING' OR decided_by IS NOT NULL),
    CHECK (state <> 'REVERSED' OR reversed_by IS NOT NULL)
);
CREATE INDEX ix_expenses_org ON expenses (organization_id, competence DESC, created_at DESC);

-- Liquidações de contas a pagar: pagamento (sai de uma conta) ou abatimento por devolução ao fornecedor.
CREATE TABLE payable_settlements (
    id              uuid PRIMARY KEY,
    payable_id      uuid          NOT NULL REFERENCES payables (id),
    kind            text          NOT NULL CHECK (kind IN ('PAYMENT', 'SUPPLIER_RETURN', 'CANCELLATION')),
    amount          numeric(19,2) NOT NULL CHECK (amount > 0),
    fin_account_id  uuid          REFERENCES fin_accounts (id),
    paid_on         date          NOT NULL,
    note            text CHECK (length(note) <= 500),
    source_id       uuid,
    state           text          NOT NULL DEFAULT 'CONFIRMED' CHECK (state IN ('CONFIRMED', 'REVERSED')),
    reversed_by     uuid          REFERENCES app_users (id),
    reversed_at     timestamptz,
    reverse_reason  text CHECK (length(reverse_reason) <= 500),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    CHECK (kind <> 'PAYMENT' OR fin_account_id IS NOT NULL)
);
CREATE INDEX ix_payable_settlements ON payable_settlements (payable_id, created_at);

-- Contas a receber: cliente (a prazo), cartão (parcelas líquidas do adquirente) e crédito com fornecedor.
CREATE TABLE receivables (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    kind            text          NOT NULL CHECK (kind IN ('CUSTOMER', 'CARD', 'SUPPLIER_CREDIT')),
    payment_id      uuid          REFERENCES payments (id),
    order_id        uuid          REFERENCES orders (id),
    supplier_id     uuid          REFERENCES suppliers (id),
    installment_no  int           NOT NULL DEFAULT 1 CHECK (installment_no >= 1),
    installments    int           NOT NULL DEFAULT 1 CHECK (installments >= installment_no),
    due_date        date          NOT NULL,
    gross           numeric(19,2) NOT NULL CHECK (gross >= 0),
    fee             numeric(19,2) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    amount          numeric(19,2) NOT NULL CHECK (amount > 0),     -- valor a receber (cartão: líquido)
    balance         numeric(19,2) NOT NULL CHECK (balance >= 0),
    state           text          NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'PARTIALLY_SETTLED', 'SETTLED',
                                                                           'CANCELLED')),
    description     text,
    version         bigint        NOT NULL DEFAULT 1,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CHECK (balance <= amount),
    CHECK (kind <> 'CARD' OR amount = gross - fee),
    UNIQUE (payment_id, installment_no)
);
CREATE INDEX ix_receivables_org ON receivables (organization_id, state, due_date);

CREATE TABLE receivable_settlements (
    id              uuid PRIMARY KEY,
    receivable_id   uuid          NOT NULL REFERENCES receivables (id),
    amount          numeric(19,2) NOT NULL CHECK (amount > 0),      -- baixa do recebível
    received        numeric(19,2) NOT NULL CHECK (received >= 0),   -- efetivamente creditado na conta
    fin_account_id  uuid          NOT NULL REFERENCES fin_accounts (id),
    received_on     date          NOT NULL,
    note            text CHECK (length(note) <= 500),
    state           text          NOT NULL DEFAULT 'CONFIRMED' CHECK (state IN ('CONFIRMED', 'REVERSED')),
    reversed_by     uuid          REFERENCES app_users (id),
    reversed_at     timestamptz,
    reverse_reason  text CHECK (length(reverse_reason) <= 500),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL REFERENCES app_users (id)
);

-- Transferência interna entre contas (FIN-05).
CREATE TABLE fin_transfers (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    from_account_id uuid          NOT NULL REFERENCES fin_accounts (id),
    to_account_id   uuid          NOT NULL REFERENCES fin_accounts (id),
    amount          numeric(19,2) NOT NULL CHECK (amount > 0),
    transfer_date   date          NOT NULL,
    note            text CHECK (length(note) <= 500),
    state           text          NOT NULL DEFAULT 'CONFIRMED' CHECK (state IN ('CONFIRMED', 'REVERSED')),
    reversed_by     uuid          REFERENCES app_users (id),
    reversed_at     timestamptz,
    reverse_reason  text CHECK (length(reverse_reason) <= 500),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    CHECK (from_account_id <> to_account_id)
);

-- Períodos mensais (FIN-07).
CREATE TABLE fin_periods (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    month           date        NOT NULL CHECK (extract(day FROM month) = 1),
    state           text        NOT NULL CHECK (state IN ('OPEN', 'CLOSED')),
    closed_at       timestamptz,
    closed_by       uuid        REFERENCES app_users (id),
    snapshot        jsonb,
    version         bigint      NOT NULL DEFAULT 1,
    UNIQUE (organization_id, month),
    CHECK (state = 'OPEN' OR (closed_by IS NOT NULL AND snapshot IS NOT NULL))
);
CREATE TABLE fin_period_events (
    id          uuid PRIMARY KEY,
    period_id   uuid        NOT NULL REFERENCES fin_periods (id),
    action      text        NOT NULL CHECK (action IN ('CLOSE', 'REOPEN_REQUEST', 'REOPEN_APPROVE', 'REOPEN_REJECT')),
    actor_id    uuid        NOT NULL REFERENCES app_users (id),
    reason      text CHECK (length(reason) <= 500),
    state       text        CHECK (state IN ('PENDING', 'DONE')),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_period_events ON fin_period_events (period_id, occurred_at);

-- Livro: nenhum lançamento em mês fechado (pela data do lançamento ou pela competência).
CREATE FUNCTION journal_period_open() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM fin_periods p WHERE p.organization_id = NEW.organization_id AND p.state = 'CLOSED'
                AND p.month IN (date_trunc('month', NEW.entry_date)::date,
                                date_trunc('month', coalesce(NEW.competence, NEW.entry_date))::date)) THEN
        RAISE EXCEPTION 'período fechado para %', coalesce(NEW.competence, NEW.entry_date) USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_journal_period_open BEFORE INSERT ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION journal_period_open();

-- Recebíveis do que já existia (sem reescrever o livro): cartões confirmados em parcelas e a prazo confirmados.
INSERT INTO receivables (id, organization_id, branch_id, kind, payment_id, order_id, installment_no, installments,
                         due_date, gross, fee, amount, balance, state, description)
SELECT gen_random_uuid(), p.organization_id, p.branch_id, 'CARD', p.id, p.order_id, i, n,
       (coalesce(p.confirmed_at, p.created_at) AT TIME ZONE 'America/Sao_Paulo')::date
           + CASE WHEN p.method = 'DEBIT' THEN 1 ELSE 30 * i END,
       CASE WHEN i < n THEN round(p.amount / n, 2) ELSE p.amount - round(p.amount / n, 2) * (n - 1) END,
       CASE WHEN i < n THEN round(p.fee / n, 2) ELSE p.fee - round(p.fee / n, 2) * (n - 1) END,
       (CASE WHEN i < n THEN round(p.amount / n, 2) ELSE p.amount - round(p.amount / n, 2) * (n - 1) END)
         - (CASE WHEN i < n THEN round(p.fee / n, 2) ELSE p.fee - round(p.fee / n, 2) * (n - 1) END),
       (CASE WHEN i < n THEN round(p.amount / n, 2) ELSE p.amount - round(p.amount / n, 2) * (n - 1) END)
         - (CASE WHEN i < n THEN round(p.fee / n, 2) ELSE p.fee - round(p.fee / n, 2) * (n - 1) END),
       'OPEN', 'Cartão ' || p.method || coalesce(' NSU ' || p.provider_reference, '')
  FROM payments p, LATERAL (SELECT coalesce(p.installments, 1) AS n) k, generate_series(1, k.n) AS i
 WHERE p.method IN ('DEBIT', 'CREDIT') AND p.state = 'CONFIRMED';

INSERT INTO receivables (id, organization_id, branch_id, kind, payment_id, order_id, due_date, gross, amount, balance,
                         state, description)
SELECT gen_random_uuid(), p.organization_id, p.branch_id, 'CUSTOMER', p.id, p.order_id,
       coalesce(p.due_date, (p.created_at AT TIME ZONE 'America/Sao_Paulo')::date), p.amount, p.amount, p.amount, 'OPEN',
       'A prazo'
  FROM payments p WHERE p.method = 'ON_ACCOUNT' AND p.state = 'CONFIRMED';
