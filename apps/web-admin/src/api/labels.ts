// Rótulos em português para recursos, ações, escopos e campos do contrato de permissões.
export const RESOURCE_LABELS: Record<string, string> = {
  "orders.pickup": "Pedidos de retirada",
  "orders.delivery": "Pedidos de entrega",
  "inventory.receipts": "Recebimentos de fornecedor",
  "inventory.stock": "Estoque",
  "inventory.losses": "Perdas de estoque",
  trips: "Viagens",
  vehicles: "Veículos",
  fuel: "Abastecimentos",
  payments: "Recebimentos de clientes",
  own_cash: "Caixa próprio",
  company_cash: "Caixa da empresa",
  expenses: "Despesas",
  taxes: "Guias e impostos",
  payroll: "Folha de pagamento",
  invoices: "Notas fiscais",
  documents: "Documentos",
  reports: "Relatórios",
  dashboard: "Visão geral (dashboard)",
  goals: "Metas",
  audit: "Histórico",
  ai: "Inteligência (IA)",
  users: "Pessoas",
  permissions: "Permissões",
  identity: "Identidade",
  catalog: "Produtos e medidas",
  customers: "Clientes",
  suppliers: "Fornecedores",
};

export const ACTION_LABELS: Record<string, string> = {
  view: "Ver",
  create: "Cadastrar",
  edit_draft: "Editar rascunho",
  request: "Solicitar",
  approve: "Aprovar",
  confirm: "Confirmar",
  cancel: "Cancelar",
  reverse: "Estornar",
  export: "Exportar",
  print: "Imprimir",
  attach: "Anexar",
  assign: "Atribuir",
  close: "Fechar",
  close_period: "Fechar período",
  reopen_period: "Reabrir período",
};

export const SCOPE_LABELS: Record<string, string> = {
  OWN: "Próprios registros",
  ASSIGNED: "Atribuídos a ele",
  SECTOR: "Do setor",
  BRANCH: "Da unidade",
  ORGANIZATION: "Toda a empresa",
};

export const FIELD_LABELS: Record<string, string> = {
  operational_contact: "Contato operacional",
  delivery_address: "Endereço de entrega",
  personal_document: "CPF/CNPJ e documentos pessoais",
  employee_salary: "Salário individual",
  bank_data: "Dados bancários",
  purchase_cost: "Custo de compra",
  margin: "Custo/margem",
};

export const CLIENT_LABELS: Record<string, string> = {
  "engenheiro-web": "Site do patrão",
  "engenheiro-gestao": "App Engenheiro Patrão",
  "engenheiro-retirada": "App Engenheiro Retirada",
  "engenheiro-entregas": "App Engenheiro Entregas",
  "engenheiro-contabil": "App Engenheiro Contábil",
};

export const SECTOR_LABELS: Record<string, string> = {
  ADMINISTRACAO: "Administração",
  RETIRADA: "Retirada e fornecedores",
  ENTREGAS: "Entregas",
  CONTABILIDADE: "Contabilidade",
};

export const TEMPLATE_LABELS: Record<string, string> = {
  ADMIN: "Administrador",
  BALCAO: "Balcão",
  RECEBIMENTO: "Recebimento de fornecedor",
  COORDENADOR_ENTREGAS: "Coordenação de entregas",
  MOTORISTA: "Motorista",
  CONTADOR: "Contador",
};

export const AUDIT_ACTION_LABELS: Record<string, string> = {
  "access.denied": "Acesso negado",
  "permissions.replace": "Alterou permissões",
  "permissions.template_applied": "Aplicou modelo de perfil",
  "membership.status": "Alterou situação da conta",
  "customers.create": "Cadastrou cliente",
  "catalog.products.price": "Alterou preço de venda",
  "orders.create": "Cadastrou pedido",
  "orders.ready": "Marcou pedido como pronto",
  "orders.fulfill": "Registrou retirada/entrega",
  "orders.cancel": "Cancelou pedido",
  "orders.return": "Registrou devolução de venda",
  "payments.register": "Registrou pagamento",
  "payments.confirm": "Conferiu pagamento",
  "payments.reject": "Recusou pagamento",
  "payments.reverse": "Estornou pagamento",
  "cash.open": "Abriu caixa",
  "cash.close": "Fechou caixa",
  "cash.review": "Conferiu fechamento de caixa",
  "catalog.products.create": "Cadastrou produto",
  "catalog.products.conversion": "Alterou conversão de medida",
  "catalog.products.confirm_unit": "Confirmou unidade comercial",
  "suppliers.create": "Cadastrou fornecedor",
  "inventory.receipts.create": "Registrou recebimento",
  "inventory.receipts.confirm": "Conferiu e confirmou recebimento",
  "inventory.receipts.return": "Devolveu ao fornecedor",
  "inventory.losses.create": "Registrou perda",
  "inventory.losses.approve": "Aprovou perda",
  "inventory.losses.reject": "Recusou perda",
  "inventory.losses.reverse": "Estornou perda",
  "inventory.adjustments.create": "Registrou contagem",
  "inventory.adjustments.approve": "Aplicou ajuste de contagem",
  "inventory.adjustments.reject": "Recusou ajuste de contagem",
  "payables.create": "Criou conta a pagar",
  "documents.attach": "Anexou documento",
  "documents.download": "Baixou documento",
  "branches.create": "Criou unidade",
  "branches.status": "Alterou situação da unidade",
};

export const label = (map: Record<string, string>, key: string) => map[key] ?? key;
