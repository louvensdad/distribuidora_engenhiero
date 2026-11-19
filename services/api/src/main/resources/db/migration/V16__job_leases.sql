-- F16: concessão de execução de jobs agendados entre várias instâncias da API. Um job só roda na instância que
-- obtiver a concessão (comando único e curto; expira sozinha se a instância cair). Só expande o esquema.
CREATE TABLE job_leases (
    name       text        PRIMARY KEY,
    holder     text        NOT NULL,
    until      timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
