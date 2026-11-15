-- DADOS FICTÍCIOS de desenvolvimento (perfil local). Migration repetível e idempotente:
-- roda depois de todas as versionadas, então novas migrations de esquema nunca ficam "fora de ordem".
-- Sem pessoas, documentos ou contatos reais.
-- Subjects coincidem com os IDs fixos dos usuários em infra/local/keycloak/engenheiro-realm.json.

INSERT INTO organizations (id, name, status) VALUES
    ('0193a000-0000-7000-8000-000000000001', 'Distribuidora de Cana Engenheiro Ltda', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO branches (id, organization_id, name, status) VALUES
    ('0193a000-0000-7000-8000-0000000000b1', '0193a000-0000-7000-8000-000000000001', 'Matriz', 'ACTIVE'),
    ('0193a000-0000-7000-8000-0000000000b2', '0193a000-0000-7000-8000-000000000001', 'Unidade Exemplo 2', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO app_users (id, issuer, external_subject, display_name, status) VALUES
    ('0193a000-0000-7000-8000-000000000a01', 'http://localhost:8180/realms/engenheiro', '00000000-0000-4000-8000-000000000a01', 'Patrão Fictício', 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000a02', 'http://localhost:8180/realms/engenheiro', '00000000-0000-4000-8000-000000000a02', 'Balcão Exemplo', 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000a03', 'http://localhost:8180/realms/engenheiro', '00000000-0000-4000-8000-000000000a03', 'Recebimento Exemplo', 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000a04', 'http://localhost:8180/realms/engenheiro', '00000000-0000-4000-8000-000000000a04', 'Coordenação Entregas Exemplo', 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000a05', 'http://localhost:8180/realms/engenheiro', '00000000-0000-4000-8000-000000000a05', 'Motorista A Exemplo', 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000a06', 'http://localhost:8180/realms/engenheiro', '00000000-0000-4000-8000-000000000a06', 'Motorista B Exemplo', 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000a07', 'http://localhost:8180/realms/engenheiro', '00000000-0000-4000-8000-000000000a07', 'Contador Exemplo', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO memberships (id, user_id, organization_id, branch_id, sector, role_template, is_admin, status) VALUES
    ('0193a000-0000-7000-8000-000000000c01', '0193a000-0000-7000-8000-000000000a01', '0193a000-0000-7000-8000-000000000001', '0193a000-0000-7000-8000-0000000000b1', 'ADMINISTRACAO', 'ADMIN', true, 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000c02', '0193a000-0000-7000-8000-000000000a02', '0193a000-0000-7000-8000-000000000001', '0193a000-0000-7000-8000-0000000000b1', 'RETIRADA', 'BALCAO', false, 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000c03', '0193a000-0000-7000-8000-000000000a03', '0193a000-0000-7000-8000-000000000001', '0193a000-0000-7000-8000-0000000000b1', 'RETIRADA', 'RECEBIMENTO', false, 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000c04', '0193a000-0000-7000-8000-000000000a04', '0193a000-0000-7000-8000-000000000001', '0193a000-0000-7000-8000-0000000000b1', 'ENTREGAS', 'COORDENADOR_ENTREGAS', false, 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000c05', '0193a000-0000-7000-8000-000000000a05', '0193a000-0000-7000-8000-000000000001', '0193a000-0000-7000-8000-0000000000b1', 'ENTREGAS', 'MOTORISTA', false, 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000c06', '0193a000-0000-7000-8000-000000000a06', '0193a000-0000-7000-8000-000000000001', '0193a000-0000-7000-8000-0000000000b1', 'ENTREGAS', 'MOTORISTA', false, 'ACTIVE'),
    ('0193a000-0000-7000-8000-000000000c07', '0193a000-0000-7000-8000-000000000a07', '0193a000-0000-7000-8000-000000000001', '0193a000-0000-7000-8000-0000000000b1', 'CONTABILIDADE', 'CONTADOR', false, 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO membership_clients (membership_id, client_id) VALUES
    ('0193a000-0000-7000-8000-000000000c01', 'engenheiro-web'),
    ('0193a000-0000-7000-8000-000000000c01', 'engenheiro-gestao'),
    ('0193a000-0000-7000-8000-000000000c02', 'engenheiro-retirada'),
    ('0193a000-0000-7000-8000-000000000c03', 'engenheiro-retirada'),
    ('0193a000-0000-7000-8000-000000000c04', 'engenheiro-entregas'),
    ('0193a000-0000-7000-8000-000000000c05', 'engenheiro-entregas'),
    ('0193a000-0000-7000-8000-000000000c06', 'engenheiro-entregas'),
    ('0193a000-0000-7000-8000-000000000c07', 'engenheiro-contabil')
ON CONFLICT DO NOTHING;
