-- F07: guias (preparar → enviar → aprovar/devolver → pagar), folha por rubricas com dado individual protegido,
-- central de documentos com versões e solicitações. Despesa reconhecida uma vez; pagamento baixa o passivo.

-- Guias e obrigações.
CREATE TABLE tax_obligations (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    kind            text          NOT NULL CHECK (kind IN ('DAS', 'ICMS', 'ISS', 'INSS', 'FGTS', 'IRRF', 'OUTRA')),
    description     text          NOT NULL CHECK (length(description) BETWEEN 1 AND 200),
    competence      date          NOT NULL CHECK (extract(day FROM competence) = 1),
    due_date        date          NOT NULL,
    amount          numeric(19,2) NOT NULL CHECK (amount > 0),
    notes           text CHECK (length(notes) <= 500),
    payroll_run_id  uuid,         -- guia de encargos/retenções de uma folha: paga o passivo dela, não cria despesa
    state           text          NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'SUBMITTED', 'RETURNED', 'APPROVED',
                                                                            'PAID', 'CANCELLED')),
    payable_id      uuid          REFERENCES payables (id),
    submitted_by    uuid          REFERENCES app_users (id),
    submitted_at    timestamptz,
    decided_by      uuid          REFERENCES app_users (id),
    decided_at      timestamptz,
    decided_note    text CHECK (length(decided_note) <= 500),
    version         bigint        NOT NULL DEFAULT 1,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    CHECK (state NOT IN ('APPROVED', 'PAID') OR (decided_by IS NOT NULL AND payable_id IS NOT NULL))
);
CREATE INDEX ix_tax_obligations_org ON tax_obligations (organization_id, due_date DESC);

-- Folha por rubricas.
CREATE TABLE payroll_runs (
    id              uuid PRIMARY KEY,
    organization_id uuid          NOT NULL REFERENCES organizations (id),
    branch_id       uuid          NOT NULL REFERENCES branches (id),
    competence      date          NOT NULL CHECK (extract(day FROM competence) = 1),
    description     text          NOT NULL CHECK (length(description) BETWEEN 1 AND 120),
    pay_date        date          NOT NULL,
    state           text          NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'SUBMITTED', 'RETURNED', 'APPROVED',
                                                                            'PAID', 'CANCELLED')),
    source          text          NOT NULL CHECK (source IN ('MANUAL', 'IMPORT')),
    source_sha256   text,
    net_payable_id  uuid          REFERENCES payables (id),
    benefits_payable_id uuid      REFERENCES payables (id),
    charges_payable_id  uuid      REFERENCES payables (id),
    submitted_by    uuid          REFERENCES app_users (id),
    submitted_at    timestamptz,
    decided_by      uuid          REFERENCES app_users (id),
    decided_at      timestamptz,
    decided_note    text CHECK (length(decided_note) <= 500),
    version         bigint        NOT NULL DEFAULT 1,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      uuid          NOT NULL REFERENCES app_users (id),
    CHECK (state NOT IN ('APPROVED', 'PAID') OR decided_by IS NOT NULL)
);
CREATE UNIQUE INDEX ux_payroll_runs_active ON payroll_runs (organization_id, branch_id, competence, description)
    WHERE state <> 'CANCELLED';
ALTER TABLE tax_obligations ADD CONSTRAINT fk_tax_payroll FOREIGN KEY (payroll_run_id) REFERENCES payroll_runs (id);

-- Itens: valor individual é dado sensível (classe employee_salary) — só sai do banco para quem tem a permissão.
CREATE TABLE payroll_items (
    id             uuid PRIMARY KEY,
    run_id         uuid          NOT NULL REFERENCES payroll_runs (id),
    membership_id  uuid          NOT NULL REFERENCES memberships (id),
    rubric         text          NOT NULL CHECK (length(rubric) BETWEEN 1 AND 60),
    kind           text          NOT NULL CHECK (kind IN ('EARNING', 'BENEFIT', 'DEDUCTION', 'ADVANCE', 'EMPLOYER_CHARGE')),
    amount         numeric(19,2) NOT NULL CHECK (amount > 0),
    line_no        int           NOT NULL CHECK (line_no > 0),
    UNIQUE (run_id, line_no)
);
CREATE INDEX ix_payroll_items_run ON payroll_items (run_id, membership_id);

-- Central de documentos: documento lógico com versões (arquivos privados) e solicitações.
CREATE TABLE accounting_documents (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    branch_id       uuid        NOT NULL REFERENCES branches (id),
    kind            text        NOT NULL CHECK (kind IN ('GUIA', 'COMPROVANTE', 'FOLHA', 'NOTA_FISCAL', 'CONTRATO', 'EXTRATO',
                                                         'OUTRO')),
    title           text        NOT NULL CHECK (length(title) BETWEEN 1 AND 160),
    competence      date        CHECK (competence IS NULL OR extract(day FROM competence) = 1),
    related_type    text        CHECK (related_type IN ('tax_obligation', 'payroll_run', 'expense', 'payable')),
    related_id      uuid,
    current_version int         NOT NULL DEFAULT 0,
    state           text        NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'ARCHIVED')),
    version         bigint      NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid        NOT NULL REFERENCES app_users (id)
);
CREATE INDEX ix_accounting_documents_org ON accounting_documents (organization_id, created_at DESC);

CREATE TABLE accounting_document_versions (
    id            uuid PRIMARY KEY,
    document_id   uuid        NOT NULL REFERENCES accounting_documents (id),
    version_no    int         NOT NULL CHECK (version_no > 0),
    file_id       uuid        NOT NULL REFERENCES documents (id),
    reason        text CHECK (length(reason) <= 500),
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid        NOT NULL REFERENCES app_users (id),
    UNIQUE (document_id, version_no)
);

CREATE TABLE document_requests (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    title           text        NOT NULL CHECK (length(title) BETWEEN 1 AND 160),
    description     text CHECK (length(description) <= 500),
    kind            text        NOT NULL,
    competence      date,
    requested_from  uuid        REFERENCES app_users (id),
    due_date        date,
    state           text        NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'FULFILLED', 'CANCELLED')),
    fulfilled_document_id uuid  REFERENCES accounting_documents (id),
    fulfilled_by    uuid        REFERENCES app_users (id),
    fulfilled_at    timestamptz,
    version         bigint      NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid        NOT NULL REFERENCES app_users (id),
    CHECK (state <> 'FULFILLED' OR fulfilled_document_id IS NOT NULL)
);
