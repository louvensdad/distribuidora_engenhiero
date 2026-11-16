-- F03: produtos, medidas/conversões, fornecedores, recebimentos, saldo, movimentos, reservas, perdas e idempotência.
-- Dinheiro em numeric(19,2); quantidades e custos de cálculo em numeric(19,6) (cerebro/03).

-- Chaves de idempotência de comandos de estoque/financeiros (CONVENCOES_E_TRANSICOES).
-- Mesma chave + mesmo conteúdo devolve o resultado original; conteúdo diferente responde 409.
CREATE TABLE idempotency_keys (
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    operation       text        NOT NULL,
    idem_key        text        NOT NULL CHECK (length(idem_key) BETWEEN 16 AND 128),
    actor_user_id   uuid        NOT NULL REFERENCES app_users (id),
    request_hash    text        NOT NULL,
    response_status int,
    response_body   jsonb,
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, operation, idem_key)
);

-- Unidade-base por produto; a unidade comercial precisa ser confirmada (D01) antes de movimentar estoque.
CREATE TABLE products (
    id                  uuid PRIMARY KEY,
    organization_id     uuid        NOT NULL REFERENCES organizations (id),
    sku                 text        NOT NULL CHECK (sku ~ '^[A-Z0-9][A-Z0-9._-]{0,39}$'),
    name                text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    base_unit           text        NOT NULL CHECK (base_unit ~ '^[A-Z_]{2,20}$'),
    base_unit_confirmed boolean     NOT NULL DEFAULT false,
    unit_confirmed_by   uuid        REFERENCES app_users (id),
    unit_confirmed_at   timestamptz,
    status              text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version             bigint      NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid        NOT NULL REFERENCES app_users (id),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          uuid        REFERENCES app_users (id),
    CHECK (NOT base_unit_confirmed OR (unit_confirmed_by IS NOT NULL AND unit_confirmed_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_products_sku ON products (organization_id, sku);
CREATE INDEX ix_products_name ON products (organization_id, name, id);

-- Conversão por produto: 1 <unit> = factor <base_unit>. Histórico: nova vigência não apaga a anterior.
CREATE TABLE unit_conversions (
    id         uuid PRIMARY KEY,
    product_id uuid          NOT NULL REFERENCES products (id),
    unit       text          NOT NULL CHECK (unit ~ '^[A-Z_]{2,20}$'),
    factor     numeric(19,6) NOT NULL CHECK (factor > 0),
    valid_from timestamptz   NOT NULL DEFAULT clock_timestamp(),
    created_by uuid          NOT NULL REFERENCES app_users (id),
    created_at timestamptz   NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_unit_conversions_product ON unit_conversions (product_id, unit, valid_from DESC);

-- Fornecedor interno: não implica usuário externo.
CREATE TABLE suppliers (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations (id),
    name              text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    contact           text,                                          -- operational_contact
    personal_document text CHECK (personal_document ~ '^([0-9]{11}|[0-9]{14})$'), -- personal_document
    status            text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version           bigint      NOT NULL DEFAULT 1,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid        NOT NULL REFERENCES app_users (id),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        uuid        REFERENCES app_users (id)
);
CREATE INDEX ix_suppliers_name ON suppliers (organization_id, name, id);
CREATE UNIQUE INDEX ux_suppliers_document ON suppliers (organization_id, personal_document)
    WHERE personal_document IS NOT NULL;

-- Recebimento: rascunho (conferência quantitativa) -> CONFIRMED (entrada no estoque com custo). Confirmado não é editável.
CREATE TABLE inventory_receipts (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    supplier_id     uuid          NOT NULL REFERENCES suppliers (id),
    status          text          NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED')),
    document_number text CHECK (length(document_number) <= 60),       -- nota/romaneio do fornecedor
    notes           text CHECK (length(notes) <= 500),
    freight_amount  numeric(19,2) CHECK (freight_amount >= 0),         -- frete de compra autorizado
    total_cost      numeric(19,2) CHECK (total_cost >= 0),             -- mercadoria + frete, na confirmação
    received_at     timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 1,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    confirmed_at    timestamptz,
    confirmed_by    uuid          REFERENCES app_users (id),           -- conferente
    CHECK (status = 'DRAFT' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND total_cost IS NOT NULL))
);
CREATE INDEX ix_receipts_org_branch ON inventory_receipts (organization_id, branch_id, created_at DESC, id DESC);

CREATE TABLE inventory_receipt_lines (
    id                uuid PRIMARY KEY,
    receipt_id        uuid          NOT NULL REFERENCES inventory_receipts (id),
    line_no           int           NOT NULL CHECK (line_no > 0),
    product_id        uuid          NOT NULL REFERENCES products (id),
    unit              text          NOT NULL,
    factor            numeric(19,6) NOT NULL CHECK (factor > 0),       -- conversão vigente na gravação
    quantity_expected numeric(19,6) CHECK (quantity_expected >= 0),
    quantity_received numeric(19,6) NOT NULL CHECK (quantity_received >= 0),
    base_quantity     numeric(19,6) NOT NULL CHECK (base_quantity >= 0),
    lot               text CHECK (length(lot) <= 60),
    unit_cost         numeric(19,6) CHECK (unit_cost >= 0),            -- por <unit>; purchase_cost
    goods_cost        numeric(19,2) CHECK (goods_cost >= 0),           -- na confirmação
    freight_share     numeric(19,2) CHECK (freight_share >= 0),        -- rateio do frete, na confirmação
    returned_base_qty numeric(19,6) NOT NULL DEFAULT 0 CHECK (returned_base_qty >= 0),
    UNIQUE (receipt_id, line_no),
    CHECK (returned_base_qty <= base_quantity)
);

-- Saldo atual por produto e unidade (filial). Disponível = físico - reservado, nunca negativo.
CREATE TABLE inventory_balances (
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    product_id      uuid          NOT NULL REFERENCES products (id),
    physical        numeric(19,6) NOT NULL DEFAULT 0 CHECK (physical >= 0),
    reserved        numeric(19,6) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    value           numeric(19,6) NOT NULL DEFAULT 0 CHECK (value >= 0),  -- valor contábil do estoque
    version         bigint        NOT NULL DEFAULT 0,
    updated_at      timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (branch_id, product_id),
    CHECK (reserved <= physical),
    CHECK (physical > 0 OR value = 0)
);

-- Livro de estoque: imutável, uma linha por (origem, evento). Saídas guardam o custo histórico usado.
CREATE TABLE stock_movements (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    product_id      uuid          NOT NULL REFERENCES products (id),
    kind            text          NOT NULL CHECK (kind IN ('RECEIPT', 'SUPPLIER_RETURN', 'LOSS', 'LOSS_REVERSAL',
                                                         'SALE_ISSUE', 'SALE_RETURN')),
    quantity        numeric(19,6) NOT NULL CHECK (quantity <> 0),        -- base, com sinal
    unit_cost       numeric(19,6) NOT NULL CHECK (unit_cost >= 0),       -- por unidade-base
    total_cost      numeric(19,6) NOT NULL,                              -- valor que entrou(+)/saiu(-)
    balance_after   numeric(19,6) NOT NULL CHECK (balance_after >= 0),
    value_after     numeric(19,6) NOT NULL CHECK (value_after >= 0),
    source_type     text          NOT NULL,
    source_id       uuid          NOT NULL,
    source_line     int           NOT NULL DEFAULT 0,
    reversal_of     uuid          REFERENCES stock_movements (id),
    reason          text,
    occurred_at     timestamptz   NOT NULL DEFAULT clock_timestamp(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    UNIQUE (source_type, source_id, source_line, kind),
    CHECK ((quantity > 0) = (total_cost >= 0))
);
CREATE INDEX ix_stock_movements_product ON stock_movements (branch_id, product_id, occurred_at DESC, id DESC);
CREATE INDEX ix_stock_movements_kind_time ON stock_movements (organization_id, kind, occurred_at);

CREATE FUNCTION stock_movements_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'stock_movements é somente inclusão (operação % negada); corrija com movimento de reversão', TG_OP;
END;
$$;
CREATE TRIGGER trg_stock_movements_immutable
    BEFORE UPDATE OR DELETE ON stock_movements
    FOR EACH ROW EXECUTE FUNCTION stock_movements_immutable();

-- Reserva: diminui o disponível, não o físico. Uma reserva por chave de operação.
CREATE TABLE stock_reservations (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    product_id      uuid          NOT NULL REFERENCES products (id),
    quantity        numeric(19,6) NOT NULL CHECK (quantity > 0),
    state           text          NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'RELEASED', 'CONSUMED',
                                                                             'EXPIRED')),
    source_type     text          NOT NULL,
    source_id       uuid          NOT NULL,
    expires_at      timestamptz,
    created_at      timestamptz   NOT NULL DEFAULT clock_timestamp(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    closed_at       timestamptz,
    UNIQUE (source_type, source_id)
);

-- Perda: motivo, quantidade, autor; baixa e custo só na aprovação.
CREATE TABLE inventory_losses (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    product_id      uuid          NOT NULL REFERENCES products (id),
    unit            text          NOT NULL,
    factor          numeric(19,6) NOT NULL CHECK (factor > 0),
    quantity        numeric(19,6) NOT NULL CHECK (quantity > 0),
    base_quantity   numeric(19,6) NOT NULL CHECK (base_quantity > 0),
    reason          text          NOT NULL CHECK (reason IN ('QUEBRA', 'DETERIORACAO', 'AVARIA_TRANSPORTE',
                                                             'FURTO', 'ERRO_CONTAGEM', 'OUTRO')),
    notes           text CHECK (length(notes) <= 500),
    status          text          NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED',
                                                                               'REVERSED')),
    movement_id     uuid          REFERENCES stock_movements (id),
    total_cost      numeric(19,2),
    version         bigint        NOT NULL DEFAULT 1,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    decided_at      timestamptz,
    decided_by      uuid          REFERENCES app_users (id),
    decision_reason text CHECK (length(decision_reason) <= 500),
    CHECK (status <> 'APPROVED' OR (movement_id IS NOT NULL AND decided_by IS NOT NULL))
);
CREATE INDEX ix_losses_org_branch ON inventory_losses (organization_id, branch_id, created_at DESC, id DESC);
