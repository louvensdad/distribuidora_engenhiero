-- F04: preço de venda, pedidos, cumprimentos, pagamentos, caixa próprio e livro financeiro gerencial.

-- Preço de venda por produto e medida, com vigência (o servidor é a fonte do preço).
CREATE TABLE product_prices (
    id         uuid PRIMARY KEY,
    product_id uuid          NOT NULL REFERENCES products (id),
    unit       text          NOT NULL CHECK (unit ~ '^[A-Z_]{2,20}$'),
    price      numeric(19,2) NOT NULL CHECK (price > 0),
    valid_from timestamptz   NOT NULL DEFAULT clock_timestamp(),
    created_by uuid          NOT NULL REFERENCES app_users (id),
    created_at timestamptz   NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_product_prices ON product_prices (product_id, unit, valid_from DESC);

-- Numeração legível por unidade (coexiste com o UUID).
CREATE TABLE order_counters (
    branch_id uuid PRIMARY KEY REFERENCES branches (id),
    last_no   bigint NOT NULL DEFAULT 0
);

CREATE TABLE orders (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    number          bigint        NOT NULL,
    type            text          NOT NULL CHECK (type IN ('PICKUP', 'DELIVERY')),
    sector          text          NOT NULL,
    customer_id     uuid          NOT NULL REFERENCES customers (id),
    state           text          NOT NULL CHECK (state IN ('CONFIRMED', 'READY', 'PARTIALLY_FULFILLED', 'FULFILLED',
                                                            'CANCELLED')),
    payment_state   text          NOT NULL DEFAULT 'UNPAID' CHECK (payment_state IN ('UNPAID', 'PARTIALLY_PAID',
                                                                                    'PAID', 'ON_ACCOUNT', 'REFUND_DUE')),
    gross_total     numeric(19,2) NOT NULL CHECK (gross_total >= 0),
    discount_total  numeric(19,2) NOT NULL CHECK (discount_total >= 0),
    total           numeric(19,2) NOT NULL CHECK (total >= 0),
    paid_amount     numeric(19,2) NOT NULL DEFAULT 0,      -- confirmado e não estornado
    pending_amount  numeric(19,2) NOT NULL DEFAULT 0,      -- aguardando conferência (ex.: Pix)
    on_account      numeric(19,2) NOT NULL DEFAULT 0,      -- a prazo autorizado
    notes           text CHECK (length(notes) <= 1000),
    version         bigint        NOT NULL DEFAULT 1,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    cancelled_at    timestamptz,
    cancelled_by    uuid          REFERENCES app_users (id),
    cancel_reason   text,
    UNIQUE (branch_id, number),
    CHECK (total = gross_total - discount_total)
);
CREATE INDEX ix_orders_org_branch ON orders (organization_id, branch_id, created_at DESC, id DESC);

CREATE TABLE order_lines (
    id                 uuid PRIMARY KEY,
    order_id           uuid          NOT NULL REFERENCES orders (id),
    line_no            int           NOT NULL CHECK (line_no > 0),
    product_id         uuid          NOT NULL REFERENCES products (id),
    unit               text          NOT NULL,
    factor             numeric(19,6) NOT NULL CHECK (factor > 0),
    quantity           numeric(19,6) NOT NULL CHECK (quantity > 0),
    base_quantity      numeric(19,6) NOT NULL CHECK (base_quantity > 0),
    unit_price         numeric(19,2) NOT NULL CHECK (unit_price > 0),   -- preço vigente do servidor
    gross              numeric(19,2) NOT NULL CHECK (gross >= 0),
    discount           numeric(19,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    net                numeric(19,2) NOT NULL CHECK (net >= 0),
    reservation_id     uuid          REFERENCES stock_reservations (id),
    fulfilled_base_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK (fulfilled_base_qty >= 0),
    cancelled_base_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK (cancelled_base_qty >= 0),
    returned_base_qty  numeric(19,6) NOT NULL DEFAULT 0 CHECK (returned_base_qty >= 0),
    recognized_net     numeric(19,2) NOT NULL DEFAULT 0,               -- receita líquida já reconhecida
    UNIQUE (order_id, line_no),
    CHECK (net = gross - discount),
    CHECK (fulfilled_base_qty + cancelled_base_qty <= base_quantity),
    CHECK (returned_base_qty <= fulfilled_base_qty)
);

-- Reserva consumida em parte: quantidade ainda reservada.
ALTER TABLE stock_reservations ADD COLUMN remaining numeric(19,6);
UPDATE stock_reservations SET remaining = CASE WHEN state = 'ACTIVE' THEN quantity ELSE 0 END;
ALTER TABLE stock_reservations ALTER COLUMN remaining SET NOT NULL;
ALTER TABLE stock_reservations ADD CONSTRAINT stock_reservations_remaining CHECK (remaining >= 0 AND remaining <= quantity);

CREATE TABLE fulfillments (
    id          uuid PRIMARY KEY,
    order_id    uuid        NOT NULL REFERENCES orders (id),
    kind        text        NOT NULL CHECK (kind IN ('FULFILLMENT', 'RETURN')),
    reason      text,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    actor_id    uuid        NOT NULL REFERENCES app_users (id)
);

CREATE TABLE fulfillment_lines (
    id             uuid PRIMARY KEY,
    fulfillment_id uuid          NOT NULL REFERENCES fulfillments (id),
    order_line_id  uuid          NOT NULL REFERENCES order_lines (id),
    base_quantity  numeric(19,6) NOT NULL CHECK (base_quantity > 0),
    net_revenue    numeric(19,2) NOT NULL,
    cost           numeric(19,6) NOT NULL,
    movement_id    uuid          NOT NULL REFERENCES stock_movements (id),
    UNIQUE (fulfillment_id, order_line_id)
);

-- Caixa próprio por turno.
CREATE TABLE cash_sessions (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    operator_id     uuid          NOT NULL REFERENCES app_users (id),
    state           text          NOT NULL CHECK (state IN ('OPEN', 'CLOSED', 'REVIEWED')),
    opening_float   numeric(19,2) NOT NULL CHECK (opening_float >= 0),
    expected        numeric(19,2),
    counted         numeric(19,2) CHECK (counted >= 0),
    difference      numeric(19,2),
    justification   text CHECK (length(justification) <= 500),
    opened_at       timestamptz   NOT NULL DEFAULT now(),
    closed_at       timestamptz,
    reviewed_by     uuid          REFERENCES app_users (id),
    reviewed_at     timestamptz,
    review_note     text CHECK (length(review_note) <= 500),
    version         bigint        NOT NULL DEFAULT 1,
    CHECK (state = 'OPEN' OR (counted IS NOT NULL AND expected IS NOT NULL AND difference = counted - expected)),
    CHECK (state <> 'REVIEWED' OR reviewed_by IS NOT NULL)
);
CREATE UNIQUE INDEX ux_cash_session_open ON cash_sessions (operator_id, branch_id) WHERE state = 'OPEN';

CREATE TABLE cash_movements (
    id              uuid PRIMARY KEY,
    cash_session_id uuid          NOT NULL REFERENCES cash_sessions (id),
    direction       text          NOT NULL CHECK (direction IN ('IN', 'OUT')),
    amount          numeric(19,2) NOT NULL CHECK (amount > 0),
    source_type     text          NOT NULL,
    source_id       uuid          NOT NULL,
    occurred_at     timestamptz   NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (source_type, source_id, direction)
);

-- Recebimentos de cliente. Situação do pagamento ≠ situação do pedido.
CREATE TABLE payments (
    id                 uuid PRIMARY KEY,
    organization_id    uuid          NOT NULL REFERENCES organizations (id),
    branch_id          uuid          NOT NULL REFERENCES branches (id),
    order_id           uuid          NOT NULL REFERENCES orders (id),
    method             text          NOT NULL CHECK (method IN ('CASH', 'PIX', 'DEBIT', 'CREDIT', 'BANK_TRANSFER',
                                                                'ON_ACCOUNT')),
    state              text          NOT NULL CHECK (state IN ('PENDING', 'CONFIRMED', 'REJECTED', 'REVERSED')),
    confirmation_kind  text          NOT NULL CHECK (confirmation_kind IN ('MANUAL_AUTHORIZED', 'PROVIDER')),
    amount             numeric(19,2) NOT NULL CHECK (amount > 0),        -- bruto aplicado ao pedido
    fee                numeric(19,2) NOT NULL DEFAULT 0 CHECK (fee >= 0), -- taxa do cartão
    net                numeric(19,2) NOT NULL,                            -- líquido a receber do adquirente
    presented_cash     numeric(19,2),
    change_given       numeric(19,2),
    installments       int CHECK (installments BETWEEN 1 AND 24),
    provider_reference text CHECK (length(provider_reference) <= 80),     -- NSU / id da transação
    document_id        uuid          REFERENCES documents (id),           -- comprovante (não confirma sozinho)
    due_date           date,                                              -- a prazo
    cash_session_id    uuid          REFERENCES cash_sessions (id),
    notes              text CHECK (length(notes) <= 500),
    version            bigint        NOT NULL DEFAULT 1,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         uuid          NOT NULL REFERENCES app_users (id),
    confirmed_at       timestamptz,
    confirmed_by       uuid          REFERENCES app_users (id),
    decided_note       text CHECK (length(decided_note) <= 500),
    reversed_at        timestamptz,
    reversed_by        uuid          REFERENCES app_users (id),
    CHECK (net = amount - fee),
    CHECK (method <> 'CASH' OR (presented_cash >= amount AND change_given = presented_cash - amount
                                AND cash_session_id IS NOT NULL)),
    CHECK (state <> 'CONFIRMED' OR confirmed_by IS NOT NULL)
);
CREATE UNIQUE INDEX ux_payments_provider_ref ON payments (organization_id, method, provider_reference)
    WHERE provider_reference IS NOT NULL;
CREATE INDEX ix_payments_order ON payments (order_id, created_at);

-- Livro financeiro gerencial: cada evento gera um lançamento único e balanceado.
CREATE TABLE journal_entries (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    branch_id       uuid        NOT NULL REFERENCES branches (id),
    source_type     text        NOT NULL,
    source_id       uuid        NOT NULL,
    event_kind      text        NOT NULL,
    entry_date      date        NOT NULL,
    reversal_of     uuid        REFERENCES journal_entries (id),
    memo            text,
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by      uuid        NOT NULL REFERENCES app_users (id),
    UNIQUE (source_type, source_id, event_kind)
);
CREATE INDEX ix_journal_org_date ON journal_entries (organization_id, entry_date);

CREATE TABLE journal_lines (
    id       uuid PRIMARY KEY,
    entry_id uuid          NOT NULL REFERENCES journal_entries (id),
    account  text          NOT NULL CHECK (account IN ('CAIXA', 'BANCO', 'RECEBIVEL_CARTAO', 'CLIENTES',
                                                       'ESTOQUE', 'RECEITA_VENDAS', 'DESCONTOS_CONCEDIDOS',
                                                       'DEVOLUCOES_VENDAS', 'CMV', 'TAXAS_CARTAO', 'FORNECEDORES',
                                                       'PERDAS_ESTOQUE', 'AJUSTES_ESTOQUE')),
    debit    numeric(19,2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit   numeric(19,2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    CHECK ((debit = 0) <> (credit = 0))
);
CREATE INDEX ix_journal_lines_entry ON journal_lines (entry_id);

-- Débito = crédito por lançamento, verificado no fim da transação; livro somente inclusão.
CREATE FUNCTION journal_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d numeric; c numeric;
BEGIN
    SELECT coalesce(sum(debit), 0), coalesce(sum(credit), 0) INTO d, c FROM journal_lines WHERE entry_id = NEW.entry_id;
    IF d <> c THEN
        RAISE EXCEPTION 'lançamento % desbalanceado (débito %, crédito %)', NEW.entry_id, d, c;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_journal_balanced AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION journal_entry_balanced();

CREATE FUNCTION journal_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'livro financeiro é somente inclusão (operação % negada); corrija com lançamento de reversão', TG_OP;
END;
$$;
CREATE TRIGGER trg_journal_entries_immutable BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION journal_immutable();
CREATE TRIGGER trg_journal_lines_immutable BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION journal_immutable();
