-- F03 (fatia): clientes. Campos sensíveis classificados; escopo por organização/unidade/criador.
CREATE TABLE customers (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL REFERENCES organizations (id),
    branch_id         uuid        NOT NULL REFERENCES branches (id),
    name              text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    contact           text,                                          -- operational_contact
    address           text,                                          -- delivery_address
    personal_document text CHECK (personal_document ~ '^([0-9]{11}|[0-9]{14})$'), -- personal_document
    status            text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version           bigint      NOT NULL DEFAULT 1,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid        NOT NULL REFERENCES app_users (id),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        uuid        REFERENCES app_users (id)
);
CREATE INDEX ix_customers_org_branch ON customers (organization_id, branch_id, name, id);
CREATE UNIQUE INDEX ux_customers_document ON customers (organization_id, personal_document)
    WHERE personal_document IS NOT NULL;
