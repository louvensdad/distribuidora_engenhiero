-- Fecha os limites da F03: obrigação a pagar do recebimento, ajuste por contagem, anexos privados.

-- Obrigação a pagar (modelo-dados: payables). Compra de estoque não é despesa: a liquidação (F06) baixa o passivo.
CREATE TABLE payables (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    source_type     text          NOT NULL,
    source_id       uuid          NOT NULL,
    supplier_id     uuid          REFERENCES suppliers (id),
    description     text          NOT NULL CHECK (length(description) BETWEEN 1 AND 200),
    competence      date          NOT NULL,
    amount          numeric(19,2) NOT NULL CHECK (amount > 0),
    balance         numeric(19,2) NOT NULL CHECK (balance >= 0),
    due_date        date,
    state           text          NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'PARTIALLY_PAID', 'PAID', 'CANCELLED')),
    version         bigint        NOT NULL DEFAULT 1,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    UNIQUE (source_type, source_id),
    CHECK (balance <= amount)
);
CREATE INDEX ix_payables_org_state ON payables (organization_id, state, due_date, id);

ALTER TABLE inventory_receipts ADD COLUMN payment_due_date date;

-- Ajuste por contagem: diferença entre o contado e o físico registrado no momento da contagem.
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_kind_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_kind_check CHECK (kind IN ('RECEIPT', 'SUPPLIER_RETURN',
    'LOSS', 'LOSS_REVERSAL', 'SALE_ISSUE', 'SALE_RETURN', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT'));

CREATE TABLE inventory_adjustments (
    id               uuid PRIMARY KEY,
    organization_id  uuid          NOT NULL REFERENCES organizations (id),
    branch_id        uuid          NOT NULL REFERENCES branches (id),
    product_id       uuid          NOT NULL REFERENCES products (id),
    counted_quantity numeric(19,6) NOT NULL CHECK (counted_quantity >= 0),   -- unidade-base
    system_quantity  numeric(19,6) NOT NULL CHECK (system_quantity >= 0),    -- físico na hora da contagem
    difference       numeric(19,6) NOT NULL CHECK (difference <> 0),
    unit_cost        numeric(19,6) CHECK (unit_cost >= 0),                   -- sobra sem custo médio (físico zero)
    notes            text          NOT NULL CHECK (length(notes) BETWEEN 1 AND 500),
    status           text          NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    movement_id      uuid          REFERENCES stock_movements (id),
    total_cost       numeric(19,2),
    version          bigint        NOT NULL DEFAULT 1,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       uuid          NOT NULL REFERENCES app_users (id),
    decided_at       timestamptz,
    decided_by       uuid          REFERENCES app_users (id),
    decision_reason  text CHECK (length(decision_reason) <= 500),
    CHECK (difference = counted_quantity - system_quantity),
    CHECK (status <> 'APPROVED' OR movement_id IS NOT NULL)
);
CREATE INDEX ix_adjustments_org_branch ON inventory_adjustments (organization_id, branch_id, created_at DESC, id DESC);

-- Anexos: metadados aqui, bytes em armazenamento privado (object_key). Só AVAILABLE é baixável.
CREATE TABLE documents (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    branch_id       uuid        REFERENCES branches (id),
    owner_type      text        NOT NULL,
    owner_id        uuid        NOT NULL,
    file_name       text        NOT NULL CHECK (length(file_name) BETWEEN 1 AND 200),
    mime            text        NOT NULL CHECK (mime IN ('image/jpeg', 'image/png', 'application/pdf')),
    size_bytes      bigint      NOT NULL CHECK (size_bytes > 0),
    sha256          text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    object_key      text        NOT NULL UNIQUE,
    state           text        NOT NULL CHECK (state IN ('UPLOADING', 'QUARANTINED', 'AVAILABLE', 'REJECTED',
                                                          'SUPERSEDED', 'ARCHIVED')),
    scan_state      text        NOT NULL,
    rejection       text,
    version         bigint      NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid        NOT NULL REFERENCES app_users (id)
);
CREATE INDEX ix_documents_owner ON documents (owner_type, owner_id, created_at);
