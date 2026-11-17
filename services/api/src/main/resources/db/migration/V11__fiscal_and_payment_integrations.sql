-- F08: notas fiscais (adaptador plugável; BLOQUEADO sem provedor/certificado/tipo definido), webhooks de pagamento
-- assinados e idempotentes, histórico de tentativas por integração e triagem de resultado incerto.

-- Configuração fiscal definida com o contador (D02/D03): sem ela, nada é transmitido.
CREATE TABLE fiscal_settings (
    organization_id uuid PRIMARY KEY REFERENCES organizations (id),
    document_type   text        CHECK (document_type IN ('NFE', 'NFCE', 'BOTH')),
    issuer_document text        CHECK (issuer_document ~ '^[0-9]{14}$'),   -- CNPJ do emitente
    state_registration text     CHECK (length(state_registration) BETWEEN 2 AND 20), -- IE (ou ISENTO)
    tax_regime      text        CHECK (tax_regime IN ('SIMPLES_NACIONAL', 'SIMPLES_EXCESSO', 'NORMAL')),
    environment     text        NOT NULL DEFAULT 'HOMOLOGACAO' CHECK (environment IN ('HOMOLOGACAO', 'PRODUCAO')),
    series          int         CHECK (series BETWEEN 1 AND 999),
    default_cfop    text        CHECK (default_cfop ~ '^[1-7][0-9]{3}$'),
    notes           text CHECK (length(notes) <= 500),
    version         bigint      NOT NULL DEFAULT 1,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid        REFERENCES app_users (id)
);

-- Dados fiscais do produto (informados pelo contador; o sistema não os deduz).
ALTER TABLE products ADD COLUMN ncm text CHECK (ncm ~ '^[0-9]{8}$');
ALTER TABLE products ADD COLUMN fiscal_origin int CHECK (fiscal_origin BETWEEN 0 AND 8);

-- Notas fiscais: rascunho → conferida → transmitida → autorizada | rejeitada | incerta; cancelamento separado.
CREATE TABLE invoices (
    id               uuid PRIMARY KEY,
    organization_id  uuid          NOT NULL REFERENCES organizations (id),
    branch_id        uuid          NOT NULL REFERENCES branches (id),
    order_id         uuid          NOT NULL REFERENCES orders (id),
    document_type    text          NOT NULL CHECK (document_type IN ('NFE', 'NFCE')),
    environment      text          NOT NULL CHECK (environment IN ('HOMOLOGACAO', 'PRODUCAO')),
    state            text          NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'READY', 'TRANSMITTING', 'UNCERTAIN',
                                       'AUTHORIZED', 'REJECTED', 'CANCEL_PENDING', 'CANCELLED', 'DISCARDED')),
    total            numeric(19,2) NOT NULL CHECK (total > 0),
    provider         text,
    external_ref     text,          -- id no provedor
    number           bigint,
    series           int,
    access_key       text CHECK (access_key ~ '^[0-9]{44}$'),
    protocol         text CHECK (length(protocol) <= 60),
    xml_document_id  uuid          REFERENCES documents (id),
    rejection_code   text CHECK (length(rejection_code) <= 20),
    rejection_reason text CHECK (length(rejection_reason) <= 500),
    cancel_reason    text CHECK (length(cancel_reason) <= 255),
    cancel_protocol  text CHECK (length(cancel_protocol) <= 60),
    attempts         int           NOT NULL DEFAULT 0,
    reviewed_by      uuid          REFERENCES app_users (id),
    reviewed_at      timestamptz,
    transmitted_by   uuid          REFERENCES app_users (id),
    transmitted_at   timestamptz,
    authorized_at    timestamptz,
    version          bigint        NOT NULL DEFAULT 1,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       uuid          NOT NULL REFERENCES app_users (id),
    CHECK (state <> 'AUTHORIZED' OR (access_key IS NOT NULL AND protocol IS NOT NULL)),
    CHECK (state <> 'REJECTED' OR rejection_reason IS NOT NULL)
);
-- Um pedido não tem duas notas vivas (rascunho, em curso ou autorizada).
CREATE UNIQUE INDEX ux_invoices_order_live ON invoices (order_id)
    WHERE state IN ('DRAFT', 'READY', 'TRANSMITTING', 'UNCERTAIN', 'AUTHORIZED', 'CANCEL_PENDING');
CREATE UNIQUE INDEX ux_invoices_access_key ON invoices (access_key) WHERE access_key IS NOT NULL;
CREATE INDEX ix_invoices_org ON invoices (organization_id, created_at DESC);

CREATE TABLE invoice_lines (
    id          uuid PRIMARY KEY,
    invoice_id  uuid          NOT NULL REFERENCES invoices (id),
    line_no     int           NOT NULL CHECK (line_no > 0),
    product_id  uuid          NOT NULL REFERENCES products (id),
    description text          NOT NULL,
    ncm         text,
    cfop        text,
    unit        text          NOT NULL,
    quantity    numeric(19,6) NOT NULL CHECK (quantity > 0),
    unit_price  numeric(19,6) NOT NULL CHECK (unit_price >= 0),
    total       numeric(19,2) NOT NULL CHECK (total >= 0),
    UNIQUE (invoice_id, line_no)
);

-- Histórico de toda tentativa com integração externa (fiscal, pagamentos, Open Finance...).
CREATE TABLE integration_attempts (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    integration     text        NOT NULL CHECK (integration IN ('FISCAL', 'PAYMENTS', 'OPEN_FINANCE')),
    operation       text        NOT NULL CHECK (length(operation) <= 40),
    entity_type     text        NOT NULL,
    entity_id       uuid        NOT NULL,
    idempotency_key text        NOT NULL,
    outcome         text        NOT NULL CHECK (outcome IN ('BLOCKED', 'SENT', 'SUCCESS', 'REJECTED', 'TIMEOUT', 'ERROR')),
    detail          text CHECK (length(detail) <= 500),
    actor_id        uuid        REFERENCES app_users (id),
    started_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at     timestamptz
);
CREATE INDEX ix_integration_attempts ON integration_attempts (organization_id, integration, started_at DESC);
CREATE INDEX ix_integration_attempts_entity ON integration_attempts (entity_type, entity_id, started_at);

-- Avisos (webhooks) recebidos dos provedores de pagamento: assinatura conferida, processamento uma vez por evento.
CREATE TABLE webhook_events (
    id              uuid PRIMARY KEY,
    provider        text        NOT NULL CHECK (length(provider) BETWEEN 1 AND 40),
    event_id        text,
    signature_valid boolean     NOT NULL,
    payload_sha256  text        NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    event_type      text,
    reference       text,
    amount          numeric(19,2),
    status          text        NOT NULL CHECK (status IN ('PROCESSED', 'DUPLICATE', 'REJECTED_SIGNATURE', 'UNMATCHED',
                                                          'IGNORED', 'ERROR')),
    detail          text CHECK (length(detail) <= 500),
    organization_id uuid        REFERENCES organizations (id),
    payment_id      uuid        REFERENCES payments (id),
    received_at     timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- Um evento válido é processado uma única vez (reenvio = DUPLICATE, sem efeito).
CREATE UNIQUE INDEX ux_webhook_events_once ON webhook_events (provider, event_id)
    WHERE signature_valid AND status IN ('PROCESSED', 'UNMATCHED', 'IGNORED');
CREATE INDEX ix_webhook_events_received ON webhook_events (received_at DESC);

-- Cobrança criada no provedor de pagamentos (Pix): código copia-e-cola e validade. A confirmação vem só pelo aviso
-- assinado do provedor (ou pela conferência manual autorizada, que continua existindo).
ALTER TABLE payments ADD COLUMN provider_name text CHECK (length(provider_name) <= 40);
ALTER TABLE payments ADD COLUMN charge_code text CHECK (length(charge_code) <= 2000);
ALTER TABLE payments ADD COLUMN charge_expires_at timestamptz;
CREATE UNIQUE INDEX ux_payments_provider_charge ON payments (provider_name, provider_reference)
    WHERE provider_name IS NOT NULL AND provider_reference IS NOT NULL;

-- Identidade técnica das integrações (avisos de provedor, consulta de resultado incerto): não faz login (DISABLED, sem vínculo); aparece como autor
-- nos lançamentos e na auditoria com actor_kind = SERVICE, nunca como uma pessoa.
INSERT INTO app_users (id, issuer, external_subject, display_name, status)
VALUES ('00000000-0000-4000-8000-00000000f008', 'urn:engenheiro:sistema', 'integracoes',
        'Integração — provedor externo', 'DISABLED');

-- XML fiscal (autorizado/cancelado) guardado como documento privado; DTD/entidades recusados pelo inspetor.
ALTER TABLE documents DROP CONSTRAINT documents_mime_check;
ALTER TABLE documents ADD CONSTRAINT documents_mime_check
    CHECK (mime IN ('image/jpeg', 'image/png', 'application/pdf', 'application/xml'));
