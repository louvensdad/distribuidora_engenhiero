-- F01: identidade, vínculos e autorização. IDs UUID gerados no servidor (UUIDv7 na aplicação).

CREATE TABLE organizations (
    id          uuid PRIMARY KEY,
    name        text        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE branches (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    name            text        NOT NULL,
    timezone        text        NOT NULL DEFAULT 'America/Sao_Paulo',
    status          text        NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name)
);

-- Pessoa interna. Sem senha local: a credencial fica no provedor OIDC.
CREATE TABLE app_users (
    id               uuid PRIMARY KEY,
    issuer           text        NOT NULL,
    external_subject text        NOT NULL,
    display_name     text        NOT NULL,
    status           text        NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (issuer, external_subject)
);

-- Vínculo pessoa x organização/unidade. Sem limite de quantidade de funcionários.
CREATE TABLE memberships (
    id              uuid PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES app_users (id),
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    branch_id       uuid        NOT NULL REFERENCES branches (id),
    sector          text        NOT NULL CHECK (sector IN ('ADMINISTRACAO', 'RETIRADA', 'ENTREGAS', 'CONTABILIDADE')),
    role_template   text        NOT NULL,
    is_admin        boolean     NOT NULL DEFAULT false,
    status          text        NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    version         bigint      NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid        REFERENCES app_users (id),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid        REFERENCES app_users (id),
    UNIQUE (user_id, organization_id)
);

-- Aplicativos/clients OIDC que o vínculo pode usar. Client sozinho não concede permissão.
CREATE TABLE membership_clients (
    membership_id uuid NOT NULL REFERENCES memberships (id),
    client_id     text NOT NULL,
    PRIMARY KEY (membership_id, client_id)
);

CREATE TABLE permission_grants (
    id            uuid PRIMARY KEY,
    membership_id uuid          NOT NULL REFERENCES memberships (id),
    resource      text          NOT NULL,
    action        text          NOT NULL,
    scope         text          NOT NULL CHECK (scope IN ('OWN', 'ASSIGNED', 'SECTOR', 'BRANCH', 'ORGANIZATION')),
    effect        text          NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
    max_amount    numeric(19, 2) CHECK (max_amount IS NULL OR max_amount >= 0),
    expires_at    timestamptz,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    uuid          REFERENCES app_users (id),
    revoked_at    timestamptz,
    revoked_by    uuid          REFERENCES app_users (id)
);
CREATE INDEX ix_permission_grants_active ON permission_grants (membership_id) WHERE revoked_at IS NULL;

CREATE TABLE field_policies (
    id            uuid PRIMARY KEY,
    membership_id uuid        NOT NULL REFERENCES memberships (id),
    field_class   text        NOT NULL,
    effect        text        NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid        REFERENCES app_users (id),
    revoked_at    timestamptz,
    revoked_by    uuid        REFERENCES app_users (id)
);
CREATE INDEX ix_field_policies_active ON field_policies (membership_id) WHERE revoked_at IS NULL;
