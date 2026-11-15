-- Trilha de auditoria: horário do servidor, ator real, resultado. Imutável pela aplicação.
CREATE TABLE audit_events (
    id              uuid PRIMARY KEY,
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    organization_id uuid,
    branch_id       uuid,
    actor_user_id   uuid,
    actor_kind      text        NOT NULL CHECK (actor_kind IN ('USER', 'SERVICE', 'ANONYMOUS')),
    client_id       text,
    action          text        NOT NULL,
    entity_type     text,
    entity_id       text,
    result          text        NOT NULL CHECK (result IN ('SUCCESS', 'DENIED', 'FAILURE')),
    reason          text,
    changes         jsonb,
    request_id      text,
    origin          text
);
CREATE INDEX ix_audit_events_org_time ON audit_events (organization_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_events_entity ON audit_events (entity_type, entity_id);

CREATE FUNCTION audit_events_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events é somente inclusão (operação % negada)', TG_OP;
END;
$$;
CREATE TRIGGER trg_audit_events_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();
CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_immutable();

-- Outbox transacional (ADR-008). Publicação a partir das fases de domínio.
CREATE TABLE outbox_events (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    aggregate_type    text        NOT NULL,
    aggregate_id      text        NOT NULL,
    aggregate_version bigint      NOT NULL,
    event_type        text        NOT NULL,
    payload           jsonb       NOT NULL,
    state             text        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts          int         NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at      timestamptz
);
CREATE INDEX ix_outbox_pending ON outbox_events (created_at) WHERE state = 'PENDING';
