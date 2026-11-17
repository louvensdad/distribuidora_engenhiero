-- Pedido do responsável (17/11/2026, ADR-027): cadastro de funcionários e cargos.
-- Funcionário é cadastro de RH e existe SEM conta de login; a conta individual (vínculo/membership) é opcional e
-- ligada depois. Dados pessoais e salário são campos protegidos (personal_document, operational_contact,
-- employee_salary): mascarados na leitura e recusados na gravação para quem não os vê.

CREATE TABLE job_positions (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL REFERENCES organizations (id),
    name               text        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    sector             text        NOT NULL CHECK (sector IN ('ADMINISTRACAO', 'RETIRADA', 'ENTREGAS', 'CONTABILIDADE')),
    -- Modelo de acesso sugerido ao dar acesso aos apps (o dono ainda escolhe; ADMIN não é sugerível).
    suggested_template text        CHECK (suggested_template IN ('BALCAO', 'RECEBIMENTO', 'COORDENADOR_ENTREGAS',
                                                                 'MOTORISTA', 'CONTADOR')),
    description        text        CHECK (length(description) <= 500),
    status             text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version            bigint      NOT NULL DEFAULT 1,
    created_at         timestamptz NOT NULL DEFAULT now(),
    created_by         uuid        NOT NULL REFERENCES app_users (id),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    updated_by         uuid        REFERENCES app_users (id)
);
CREATE UNIQUE INDEX ux_job_positions_name ON job_positions (organization_id, lower(name));

CREATE TABLE employees (
    id                uuid PRIMARY KEY,
    organization_id   uuid          NOT NULL REFERENCES organizations (id),
    branch_id         uuid          NOT NULL REFERENCES branches (id),
    full_name         text          NOT NULL CHECK (length(full_name) BETWEEN 1 AND 160),
    job_position_id   uuid          NOT NULL REFERENCES job_positions (id),
    duties            text          CHECK (length(duties) <= 500),        -- função/atividades do dia a dia
    admission_date    date          NOT NULL,
    termination_date  date,
    status            text          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ON_LEAVE', 'TERMINATED')),
    work_schedule     text          CHECK (length(work_schedule) <= 120),
    phone             text          CHECK (length(phone) <= 40),          -- operational_contact
    cpf               text          CHECK (cpf ~ '^[0-9]{11}$'),          -- personal_document
    birth_date        date,                                                -- personal_document
    address           text          CHECK (length(address) <= 300),       -- personal_document
    base_salary       numeric(19,2) CHECK (base_salary >= 0),             -- employee_salary
    notes             text          CHECK (length(notes) <= 1000),
    membership_id     uuid          UNIQUE REFERENCES memberships (id),    -- conta de login (opcional)
    version           bigint        NOT NULL DEFAULT 1,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        uuid          NOT NULL REFERENCES app_users (id),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        uuid          REFERENCES app_users (id),
    CHECK (status <> 'TERMINATED' OR termination_date IS NOT NULL),
    CHECK (termination_date IS NULL OR termination_date >= admission_date)
);
CREATE UNIQUE INDEX ux_employees_cpf ON employees (organization_id, cpf) WHERE cpf IS NOT NULL;
CREATE INDEX ix_employees_org_name ON employees (organization_id, full_name, id);
