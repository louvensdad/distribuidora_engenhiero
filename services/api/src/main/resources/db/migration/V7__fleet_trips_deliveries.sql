-- F05: pedido de entrega (endereço, janela, contato, pagamento combinado), frota, viagens, paradas, ocorrências,
-- odômetro monotônico, gastos de veículo (combustível/manutenção/...) e regra versionada de rateio analítico.

-- Dados de entrega no próprio pedido (o motorista vê o necessário da entrega, não o cadastro do cliente).
ALTER TABLE orders ADD COLUMN delivery_address   text CHECK (length(delivery_address) <= 300);       -- delivery_address
ALTER TABLE orders ADD COLUMN delivery_reference text CHECK (length(delivery_reference) <= 200);     -- delivery_address
ALTER TABLE orders ADD COLUMN delivery_contact   text CHECK (length(delivery_contact) <= 100);       -- operational_contact
ALTER TABLE orders ADD COLUMN window_start       timestamptz;
ALTER TABLE orders ADD COLUMN window_end         timestamptz;
ALTER TABLE orders ADD COLUMN agreed_payment     text CHECK (agreed_payment IN ('CASH', 'PIX', 'DEBIT', 'CREDIT',
                                                                                'BANK_TRANSFER', 'ON_ACCOUNT'));
ALTER TABLE orders ADD CONSTRAINT orders_delivery_required
    CHECK (type = 'PICKUP' OR (delivery_address IS NOT NULL AND agreed_payment IS NOT NULL)) NOT VALID;
ALTER TABLE orders ADD CONSTRAINT orders_window_order
    CHECK (window_start IS NULL OR window_end IS NULL OR window_end > window_start);

-- Frota.
CREATE TABLE vehicles (
    id                      uuid PRIMARY KEY,
    organization_id         uuid          NOT NULL REFERENCES organizations (id),
    branch_id               uuid          NOT NULL REFERENCES branches (id),
    plate                   text          NOT NULL CHECK (plate ~ '^[A-Z]{3}[0-9][A-Z0-9][0-9]{2}$'),
    model                   text          NOT NULL CHECK (length(model) BETWEEN 1 AND 80),
    capacity_quantity       numeric(19,6) NOT NULL CHECK (capacity_quantity > 0),
    capacity_unit           text          NOT NULL CHECK (capacity_unit ~ '^[A-Z_]{2,20}$'),
    status                  text          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE')),
    odometer_km             numeric(12,1) NOT NULL CHECK (odometer_km >= 0),
    next_service_km         numeric(12,1) CHECK (next_service_km >= 0),
    registration_valid_until date,          -- licenciamento
    insurance_valid_until   date,
    notes                   text CHECK (length(notes) <= 500),
    version                 bigint        NOT NULL DEFAULT 1,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    created_by              uuid          NOT NULL REFERENCES app_users (id),
    UNIQUE (organization_id, plate)
);

-- Leituras de odômetro: somente inclusão; regressão só por correção aprovada (kind = CORRECTION).
CREATE TABLE odometer_readings (
    id          uuid PRIMARY KEY,
    vehicle_id  uuid          NOT NULL REFERENCES vehicles (id),
    reading     numeric(12,1) NOT NULL CHECK (reading >= 0),
    kind        text          NOT NULL CHECK (kind IN ('INITIAL', 'DEPARTURE', 'RETURN', 'FUEL', 'MAINTENANCE',
                                                       'CORRECTION')),
    source_type text,
    source_id   uuid,
    reason      text CHECK (length(reason) <= 500),
    recorded_at timestamptz   NOT NULL DEFAULT clock_timestamp(),
    recorded_by uuid          NOT NULL REFERENCES app_users (id),
    CHECK (kind <> 'CORRECTION' OR reason IS NOT NULL)
);
CREATE INDEX ix_odometer_vehicle ON odometer_readings (vehicle_id, recorded_at);

CREATE FUNCTION odometer_monotonic() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    current_km numeric(12,1);
BEGIN
    SELECT odometer_km INTO current_km FROM vehicles WHERE id = NEW.vehicle_id FOR UPDATE;
    IF NEW.kind <> 'CORRECTION' AND NEW.kind <> 'INITIAL' AND NEW.reading < current_km THEN
        RAISE EXCEPTION 'odômetro regressivo (% < %) sem correção aprovada', NEW.reading, current_km
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.kind = 'CORRECTION' OR NEW.reading > current_km THEN
        UPDATE vehicles SET odometer_km = NEW.reading WHERE id = NEW.vehicle_id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_odometer_monotonic BEFORE INSERT ON odometer_readings
    FOR EACH ROW EXECUTE FUNCTION odometer_monotonic();

CREATE FUNCTION odometer_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'odometer_readings é somente inclusão (operação % negada)', TG_OP;
END;
$$;
CREATE TRIGGER trg_odometer_immutable BEFORE UPDATE OR DELETE ON odometer_readings
    FOR EACH ROW EXECUTE FUNCTION odometer_immutable();

-- Viagens: DRAFT -> ASSIGNED -> DEPARTED -> RETURNED -> RECONCILED; CANCELLED só antes da saída.
CREATE TABLE trip_counters (
    branch_id uuid PRIMARY KEY REFERENCES branches (id),
    last_no   bigint NOT NULL DEFAULT 0
);

CREATE TABLE trips (
    id                 uuid PRIMARY KEY,
    organization_id    uuid          NOT NULL REFERENCES organizations (id),
    branch_id          uuid          NOT NULL REFERENCES branches (id),
    number             bigint        NOT NULL,
    sector             text          NOT NULL DEFAULT 'ENTREGAS',
    state              text          NOT NULL CHECK (state IN ('DRAFT', 'ASSIGNED', 'DEPARTED', 'RETURNED', 'RECONCILED',
                                                               'CANCELLED')),
    vehicle_id         uuid          REFERENCES vehicles (id),
    driver_id          uuid          REFERENCES app_users (id),
    planned_date       date          NOT NULL,
    notes              text CHECK (length(notes) <= 500),
    departed_at        timestamptz,
    departure_odometer numeric(12,1),
    returned_at        timestamptz,
    return_odometer    numeric(12,1),
    returned_by        uuid          REFERENCES app_users (id),
    cash_collected     numeric(19,2),
    cash_expenses      numeric(19,2),
    cash_expected      numeric(19,2),
    cash_declared      numeric(19,2) CHECK (cash_declared >= 0),
    cash_difference    numeric(19,2),
    return_note        text CHECK (length(return_note) <= 500),
    reconciled_at      timestamptz,
    reconciled_by      uuid          REFERENCES app_users (id),
    reconcile_note     text CHECK (length(reconcile_note) <= 500),
    cancelled_at       timestamptz,
    cancel_reason      text CHECK (length(cancel_reason) <= 500),
    version            bigint        NOT NULL DEFAULT 1,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         uuid          NOT NULL REFERENCES app_users (id),
    UNIQUE (branch_id, number),
    CHECK (state IN ('DRAFT', 'CANCELLED') OR (vehicle_id IS NOT NULL AND driver_id IS NOT NULL)),
    CHECK (state NOT IN ('DEPARTED', 'RETURNED', 'RECONCILED') OR departure_odometer IS NOT NULL),
    CHECK (return_odometer IS NULL OR return_odometer >= departure_odometer),
    CHECK (state NOT IN ('RETURNED', 'RECONCILED')
           OR (return_odometer IS NOT NULL AND cash_declared IS NOT NULL AND cash_expected IS NOT NULL
               AND cash_difference = cash_declared - cash_expected)),
    CHECK (state <> 'RECONCILED' OR (reconciled_by IS NOT NULL AND reconciled_by <> driver_id))
);
CREATE INDEX ix_trips_org_branch ON trips (organization_id, branch_id, created_at DESC, id DESC);
-- Indisponível: o mesmo veículo/motorista não fica em duas viagens ativas.
CREATE UNIQUE INDEX ux_trips_vehicle_active ON trips (vehicle_id) WHERE state IN ('ASSIGNED', 'DEPARTED');
CREATE UNIQUE INDEX ux_trips_driver_active ON trips (driver_id) WHERE state IN ('ASSIGNED', 'DEPARTED');

-- Paradas: um pedido fica em no máximo uma viagem aberta.
CREATE TABLE trip_stops (
    id             uuid PRIMARY KEY,
    trip_id        uuid        NOT NULL REFERENCES trips (id),
    order_id       uuid        NOT NULL REFERENCES orders (id),
    seq            int         NOT NULL CHECK (seq > 0),
    state          text        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'DELIVERED', 'PARTIAL', 'FAILED',
                                                                           'REMOVED')),
    is_open        boolean     NOT NULL DEFAULT true,
    recipient_name text CHECK (length(recipient_name) <= 120),
    delivered_at   timestamptz,
    fulfillment_id uuid        REFERENCES fulfillments (id),
    delivered_by   uuid        REFERENCES app_users (id),
    version        bigint      NOT NULL DEFAULT 1,
    CHECK (state NOT IN ('DELIVERED', 'PARTIAL') OR (fulfillment_id IS NOT NULL AND recipient_name IS NOT NULL))
);
CREATE UNIQUE INDEX ux_trip_stops_open_order ON trip_stops (order_id) WHERE is_open;
CREATE INDEX ix_trip_stops_trip ON trip_stops (trip_id, seq);

CREATE TABLE trip_incidents (
    id               uuid PRIMARY KEY,
    trip_id          uuid        NOT NULL REFERENCES trips (id),
    stop_id          uuid        REFERENCES trip_stops (id),
    kind             text        NOT NULL CHECK (kind IN ('CUSTOMER_ABSENT', 'WRONG_ADDRESS', 'REFUSED', 'PARTIAL',
                                                          'DAMAGED', 'VEHICLE', 'OTHER')),
    requested_action text        NOT NULL CHECK (requested_action IN ('RESCHEDULE', 'RETURN', 'NONE')),
    description      text        NOT NULL CHECK (length(description) BETWEEN 1 AND 500),
    created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by       uuid        NOT NULL REFERENCES app_users (id)
);
CREATE INDEX ix_trip_incidents_trip ON trip_incidents (trip_id, created_at);

-- Gasto real do veículo/viagem, registrado uma vez. Cadastro ≠ pagamento: COMPANY vira conta a pagar na aprovação;
-- DRIVER_CASH sai do caixa do motorista no registro (adiantamento) e vira despesa na aprovação.
CREATE TABLE vehicle_expenses (
    id               uuid PRIMARY KEY,
    organization_id  uuid          NOT NULL REFERENCES organizations (id),
    branch_id        uuid          NOT NULL REFERENCES branches (id),
    vehicle_id       uuid          NOT NULL REFERENCES vehicles (id),
    trip_id          uuid          REFERENCES trips (id),
    kind             text          NOT NULL CHECK (kind IN ('FUEL', 'MAINTENANCE', 'TIRES', 'INSURANCE', 'DOCUMENTS',
                                                            'TOLL', 'OTHER')),
    occurred_on      date          NOT NULL,
    amount           numeric(19,2) NOT NULL CHECK (amount > 0),
    paid_with        text          NOT NULL CHECK (paid_with IN ('DRIVER_CASH', 'COMPANY')),
    cash_session_id  uuid          REFERENCES cash_sessions (id),
    odometer         numeric(12,1) CHECK (odometer >= 0),
    liters           numeric(12,3) CHECK (liters > 0 AND liters <= 2000),
    fuel_type        text CHECK (fuel_type IN ('DIESEL', 'GASOLINA', 'ETANOL', 'GNV')),
    full_tank        boolean,
    station          text CHECK (length(station) <= 120),
    maintenance_type text CHECK (maintenance_type IN ('PREVENTIVE', 'CORRECTIVE')),
    workshop         text CHECK (length(workshop) <= 120),
    parts            text CHECK (length(parts) <= 300),
    next_service_km  numeric(12,1),
    description      text CHECK (length(description) <= 500),
    state            text          NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED')),
    decided_by       uuid          REFERENCES app_users (id),
    decided_at       timestamptz,
    decided_note     text CHECK (length(decided_note) <= 500),
    version          bigint        NOT NULL DEFAULT 1,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       uuid          NOT NULL REFERENCES app_users (id),
    CHECK (kind <> 'FUEL' OR (liters IS NOT NULL AND odometer IS NOT NULL AND fuel_type IS NOT NULL
                              AND full_tank IS NOT NULL)),
    CHECK (paid_with <> 'DRIVER_CASH' OR cash_session_id IS NOT NULL),
    CHECK (state = 'PENDING' OR decided_by IS NOT NULL)
);
CREATE INDEX ix_vehicle_expenses_vehicle ON vehicle_expenses (vehicle_id, occurred_on DESC, created_at DESC);
CREATE INDEX ix_vehicle_expenses_trip ON vehicle_expenses (trip_id) WHERE trip_id IS NOT NULL;

-- Regra de rateio analítico versionada (não gera lançamento financeiro).
CREATE TABLE allocation_rules (
    code        text        NOT NULL,
    version     int         NOT NULL CHECK (version > 0),
    description text        NOT NULL,
    valid_from  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (code, version)
);
INSERT INTO allocation_rules (code, version, description) VALUES
    ('TRIP_COST', 1, 'Custo real da viagem rateado proporcionalmente à quantidade efetivamente entregue em cada parada, '
                  || 'na medida de capacidade do veículo; centavos de diferença na última parada; sem entrega, não alocado.');

-- Recebimento feito durante a viagem (prestação de contas do motorista).
ALTER TABLE payments ADD COLUMN trip_id uuid REFERENCES trips (id);
CREATE INDEX ix_payments_trip ON payments (trip_id) WHERE trip_id IS NOT NULL;

-- Novas contas do livro gerencial.
ALTER TABLE journal_lines DROP CONSTRAINT journal_lines_account_check;
ALTER TABLE journal_lines ADD CONSTRAINT journal_lines_account_check CHECK (account IN ('CAIXA', 'BANCO',
    'RECEBIVEL_CARTAO', 'CLIENTES', 'ESTOQUE', 'RECEITA_VENDAS', 'DESCONTOS_CONCEDIDOS', 'DEVOLUCOES_VENDAS', 'CMV',
    'TAXAS_CARTAO', 'FORNECEDORES', 'PERDAS_ESTOQUE', 'AJUSTES_ESTOQUE', 'DESPESAS_VEICULOS',
    'ADIANTAMENTOS_FUNCIONARIOS'));
