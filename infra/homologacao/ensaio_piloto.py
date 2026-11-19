#!/usr/bin/env python3
"""Ensaio do piloto na HOMOLOGAÇÃO (F17: UAT-01, UAT-02 e UAT-03) — um dia de operação de ponta a ponta.

1. Entrega da administração: o patrão (site, sessão com TOTP) cadastra cargos e funcionários, dá acesso aos apps
   (contas criadas no IdP pelo provisionamento, senha temporária vista uma vez) e libera no mapa de acessos o que cada
   função precisa além do modelo.
2. Cada pessoa entra pelo client OIDC do PRÓPRIO app (PKCE S256), troca a senha temporária e faz o seu papel:
   recebedor (Retirada) registra a chegada sem ver custo · patrão confere com custo · balconista (Retirada) abre o
   caixa, vende, recebe em dinheiro e entrega · coordenador (Entregas) cadastra veículo, pedido de entrega e viagem ·
   motorista (Entregas) sai, entrega, recebe e volta · contador (Contábil) envia a guia · patrão aprova e paga.
3. Fiscalização: cada um tenta o que não pode (custo, relatório, viagem alheia, aprovar a própria guia) e é negado;
   a central do patrão mostra cada movimento com o ator e o app de origem.
4. A conciliação do período (relatório × entregas, estoque, caixas e livro) tem de fechar.

Dados fictícios. Nada de senha, token ou TOTP é impresso; as senhas novas ficam em
~/.config/engenheiro/homologacao/ensaio/<execução>.json (600) para o ensaio manual nos aparelhos.
"""
import base64
import hashlib
import json
import os
import re
import secrets
import sys
import time
import urllib.parse
import uuid
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sessao  # noqa: E402
from sessao import LOGIN, SITE, browser, call, cookie, form_action  # noqa: E402

RUN = time.strftime("%m%d%H%M%S")
HOJE = date.today()
falhas = []


def check(ok, msg):
    print(("  ✓ " if ok else "  ✗ ") + msg)
    if not ok:
        falhas.append(msg)
    return ok


def must(ok, msg):
    if not check(ok, msg):
        print("RESULTADO: FAIL (interrompido)")
        sys.exit(1)


def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


class Pessoa:
    """Uma conta e o canal dela: sessão do site (patrão) ou token do app (funcionários)."""

    def __init__(self, nome, op, xsrf=None, token=None, client=None):
        self.nome, self.op, self.xsrf, self.token, self.client = nome, op, xsrf, token, client

    def req(self, path, body=None, version=None, idem=True):
        h = {}
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        if self.xsrf and body is not None:
            h["X-XSRF-TOKEN"] = self.xsrf
        if body is not None and idem:
            h["Idempotency-Key"] = "ensaio-" + uuid.uuid4().hex
        if version is not None:
            h["If-Match"] = f'"{version}"'
        data = None if body is None else json.dumps(body)
        if body is not None:
            h["Content-Type"] = "application/json"
        s, hd, txt = call(self.op, SITE + path, data, h)
        try:
            j = json.loads(txt) if txt else {}
        except ValueError:
            j = {}
        return s, j, hd

    def upload(self, path, name, ctype, content):
        b = "----ensaio" + uuid.uuid4().hex
        data = (f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: {ctype}\r\n\r\n'
                ).encode() + content + f"\r\n--{b}--\r\n".encode()
        h = {"Content-Type": f"multipart/form-data; boundary={b}", "Idempotency-Key": "ensaio-" + uuid.uuid4().hex}
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        if self.xsrf:
            h["X-XSRF-TOKEN"] = self.xsrf
        s, _, txt = call(self.op, SITE + path, data, h)
        return s, (json.loads(txt) if txt.startswith("{") else {})


def app_login(client, scheme, username, temp, nova):
    """Login como o app faz (AppAuth): PKCE S256 no client público do app, troca da senha temporária, código → token."""
    op, _ = browser()
    verifier = b64url(secrets.token_bytes(32))
    challenge = b64url(hashlib.sha256(verifier.encode()).digest())
    redirect = scheme + ":/oauth2redirect"
    q = urllib.parse.urlencode({"client_id": client, "response_type": "code", "scope": "openid", "redirect_uri": redirect,
                                "code_challenge": challenge, "code_challenge_method": "S256", "state": secrets.token_hex(8)})
    s, h, page = call(op, LOGIN + "/realms/engenheiro/protocol/openid-connect/auth?" + q)
    must(s == 200, f"{client}: página de login do IdP por HTTPS (HTTP {s})")
    s, h, page = call(op, form_action(page), {"username": username, "password": temp, "credentialId": ""}, {"form": True})
    trocou = False
    for _ in range(6):
        if s == 302 and h["Location"].startswith(redirect):
            break
        if s == 302:
            s, h, page = call(op, h["Location"])
        elif s == 200 and "password-new" in page:
            trocou = True
            s, h, page = call(op, form_action(page), {"password-new": nova, "password-confirm": nova}, {"form": True})
        else:
            t = re.search(r"<title>([^<]*)", page or "")
            campos = sorted(set(re.findall(r'name="([a-zA-Z-]+)"', page or "")))
            erro = re.search(r'(?:input-error|alert-error|kc-feedback-text)[^>]*>\s*([^<]{1,160})', page or "")
            must(False, f"{client}: login de {username} parou em HTTP {s} ({t and t.group(1)}; campos {campos};"
                        f" aviso: {erro and erro.group(1).strip()})")
    code = urllib.parse.parse_qs(urllib.parse.urlparse(h["Location"]).query)["code"][0]
    s, _, txt = call(op, LOGIN + "/realms/engenheiro/protocol/openid-connect/token",
                     {"grant_type": "authorization_code", "code": code, "redirect_uri": redirect, "client_id": client,
                      "code_verifier": verifier}, {"form": True})
    must(s == 200, f"{client}: código trocado por token com o verificador PKCE (HTTP {s})")
    return Pessoa(username, op, token=json.loads(txt)["access_token"], client=client), trocou


PDF = (b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj "
       b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n")

FUNCOES = [  # cargo, setor, modelo, client do app, esquema de retorno, nome fictício
    ("Balconista", "RETIRADA", "BALCAO", "engenheiro-retirada", "br.com.engenheiro.retirada", "Paula do Balcão (ensaio)"),
    ("Recebedor", "RETIRADA", "RECEBIMENTO", "engenheiro-retirada", "br.com.engenheiro.retirada", "João do Recebimento (ensaio)"),
    ("Coordenador de entregas", "ENTREGAS", "COORDENADOR_ENTREGAS", "engenheiro-entregas", "br.com.engenheiro.entregas",
     "Marcos da Coordenação (ensaio)"),
    ("Motorista", "ENTREGAS", "MOTORISTA", "engenheiro-entregas", "br.com.engenheiro.entregas", "Carlos Motorista (ensaio)"),
    ("Contador", "CONTABILIDADE", "CONTADOR", "engenheiro-contabil", "br.com.engenheiro.contabil", "Fernanda Contadora (ensaio)"),
]
EXTRAS = {  # o que o dono libera no mapa de acessos além do modelo (mesmo do ensaio local, ADR-026/F05)
    "BALCAO": [("payments", a, "BRANCH") for a in ("view", "create", "confirm")]
              + [("own_cash", a, "OWN") for a in ("view", "create", "close")] + [("orders.pickup", "confirm", "OWN")],
    "MOTORISTA": [("payments", "create", "BRANCH"), ("payments", "confirm", "BRANCH"), ("own_cash", "view", "OWN"),
                  ("own_cash", "create", "OWN"), ("trips", "close", "ASSIGNED")],
    "COORDENADOR_ENTREGAS": [("trips", "assign", "SECTOR"), ("vehicles", "create", "BRANCH")],
}

print(f"Ensaio do piloto na homologação {SITE} — execução {RUN}")

# ---------------------------------------------------------------- 1. entrega da administração ao dono
print("1) Patrão cadastra a equipe e libera os acessos (site)")
op, jar, s, _ = sessao.login("patrao")
must(s in (200, 302), "patrão entrou no site com senha + TOTP")
P = Pessoa("patrao", op, xsrf=cookie(jar, "XSRF-TOKEN").value)
s, me, _ = P.req("/api/v1/me/capabilities")
branch = (me.get("branch") or {}).get("id")
must(s == 200 and branch, "patrão: capacidades e unidade lidas")
s, prov, _ = P.req("/api/v1/employees/unlinked-accounts")
must(s == 200 and prov.get("account_creation") == "CONFIGURED",
     f"criação de contas de funcionários configurada (estado: {prov.get('account_creation')})")

s, cargos, _ = P.req("/api/v1/job-positions")
existentes = {c["name"]: c["id"] for c in cargos.get("items", [])}
contas = {}
for cargo, setor, modelo, client, scheme, nome in FUNCOES:
    pid = existentes.get(cargo)
    if not pid:
        s, c, _ = P.req("/api/v1/job-positions", {"name": cargo, "sector": setor, "suggested_template": modelo,
                                                  "description": "Cargo do ensaio do piloto", "status": "ACTIVE"})
        must(s == 201, f"cargo {cargo} criado (HTTP {s})")
        pid = c["id"]
    s, e, _ = P.req("/api/v1/employees", {"full_name": nome, "branch_id": branch, "job_position_id": pid,
                                          "admission_date": HOJE.isoformat(), "status": "ACTIVE",
                                          "duties": "Ensaio do piloto"})
    must(s == 201, f"funcionário {nome} cadastrado (HTTP {s})")
    user = f"ensaio{RUN}.{modelo.lower().split('_')[0]}"
    s, a, _ = P.req(f"/api/v1/employees/{e['id']}/access", {"username": user, "role_template": modelo})
    must(s == 200 and a.get("temporary_password"), f"acesso ao app dado a {nome}: conta {user} criada no IdP")
    uid = a["employee"]["account"]["user_id"]
    contas[modelo] = {"user": user, "temp": a["temporary_password"], "nova": secrets.token_urlsafe(18), "uid": uid,
                      "client": client, "scheme": scheme, "nome": nome}
    if modelo in EXTRAS:
        s, pol, hd = P.req(f"/api/v1/users/{uid}/access-policy")
        grants = [{k: g.get(k) for k in ("resource", "action", "scope", "effect", "max_amount", "expires_at")}
                  for g in pol["grants"]]
        grants += [{"resource": r, "action": ac, "scope": sc, "effect": "ALLOW"} for r, ac, sc in EXTRAS[modelo]]
        etag = hd.get("ETag", "").strip('"')
        s, _, _ = P.req(f"/api/v1/users/{uid}/access-policy",
                        {"grants": grants, "fields": pol.get("fields", []), "clients": pol["clients"],
                         "reason": "Ensaio do piloto: liberar o necessário à função"}, version=etag, idem=False)
        check(s == 200, f"mapa de acessos de {nome}: {len(EXTRAS[modelo])} permissões além do modelo (HTTP {s})")
d = os.path.join(sessao.D, "ensaio")
os.makedirs(d, mode=0o700, exist_ok=True)
fd = os.open(os.path.join(d, f"{RUN}.json"), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.write(fd, json.dumps({m: {"usuario": c["user"], "senha": c["nova"], "app": c["client"]} for m, c in contas.items()},
                        ensure_ascii=False, indent=1).encode())
os.close(fd)

# ---------------------------------------------------------------- 2. cada um no próprio app
print("2) Cada pessoa entra pelo próprio app (PKCE, troca da senha temporária)")
A = {}
for modelo, c in contas.items():
    A[modelo], trocou = app_login(c["client"], c["scheme"], c["user"], c["temp"], c["nova"])
    check(trocou, f"{c['nome']} trocou a senha temporária no primeiro acesso ({c['client']})")
    s, mm, _ = A[modelo].req("/api/v1/me/capabilities")
    check(s == 200, f"{c['nome']} entra na API pelo app {c['client']}")
s, _, _ = Pessoa("x", browser()[0], token=None).req("/api/v1/me/capabilities")
check(s == 401, f"sem token: 401 ({s})")

print("3) Operação do dia")
s, p, hd = P.req("/api/v1/products", {"sku": f"CANA-{RUN}", "name": "Cana caiana (ensaio)", "base_unit": "DUZIA",
                                      "base_unit_confirmed": True})
must(s == 201, "patrão cadastra o produto")
prod = p["id"]
s, _, _ = P.req(f"/api/v1/products/{prod}/prices", {"price": "20.00"}, version=hd.get("ETag", "").strip('"'), idem=False)
check(s in (200, 201), f"patrão define o preço R$ 20,00/dz (HTTP {s})")
s, sup, _ = P.req("/api/v1/suppliers", {"name": f"Sítio do ensaio {RUN}"}, idem=False)
must(s == 201, "patrão cadastra o fornecedor")
s, cli, _ = P.req("/api/v1/customers", {"name": f"Mercado do ensaio {RUN}", "address": "Rua Exemplo, 100 — Centro"},
                  idem=False)
must(s == 201, "patrão cadastra o cliente")
s, banco, _ = P.req("/api/v1/finance/accounts", {"name": f"Banco do ensaio {RUN}", "kind": "BANK", "opening_balance": "5000.00"})
must(s == 201, "patrão cadastra a conta bancária")

R = A["RECEBIMENTO"]
s, rc, _ = R.req("/api/v1/inventory/receipts", {"supplier_id": sup["id"], "document_number": f"NF-{RUN}",
                                                "lines": [{"product_id": prod, "quantity_expected": "500",
                                                           "quantity_received": "500"}]})
check(s == 201, f"recebedor registra a chegada de 500 dz no app Retirada (HTTP {s})")
s, vista, _ = R.req(f"/api/v1/inventory/receipts/{rc.get('id')}")
check("unit_cost" not in json.dumps(vista) or all(l.get("unit_cost") in (None, "") for l in vista.get("lines", [])),
      "recebedor não vê custo de compra")
s, rcp, _ = P.req(f"/api/v1/inventory/receipts/{rc['id']}")
linhas = [{"line_no": l["line_no"], "unit_cost": "7.50"} for l in rcp.get("lines", [])]
s, _, _ = P.req(f"/api/v1/inventory/receipts/{rc['id']}/confirm", {"lines": linhas}, version=rcp.get("version"))
check(s == 200, f"patrão confere o custo (R$ 7,50/dz) e confirma a entrada (HTTP {s})")

B = A["BALCAO"]
s, _, _ = B.req("/api/v1/cash-sessions", {"opening_float": "100.00"})
check(s == 201, f"balconista abre o caixa com troco de R$ 100,00 (HTTP {s})")
s, o1, _ = B.req("/api/v1/orders", {"type": "PICKUP", "customer_id": cli["id"],
                                    "items": [{"product_id": prod, "quantity": "100"}]})
must(s == 201, f"balconista cria pedido de retirada de 100 dz (HTTP {s}) — total {o1.get('total')}")
s, _, _ = B.req(f"/api/v1/orders/{o1['id']}/payments", {"parts": [{"method": "CASH", "amount": o1["total"],
                                                                   "presented_cash": "2050.00"}]})
check(s == 200, f"balconista recebe R$ 2.050,00 em dinheiro (troco R$ 50,00) (HTTP {s})")
s, _, _ = B.req(f"/api/v1/orders/{o1['id']}/fulfillments", {})
check(s == 200, f"balconista entrega a mercadoria (HTTP {s})")
s, cur, _ = B.req("/api/v1/cash-sessions/current")
sess = cur.get("session") or {}
esperado = sess.get("expected") or sess.get("cash_expected")
s, fech, _ = B.req(f"/api/v1/cash-sessions/{sess.get('id')}/close", {"counted": "2100.00"}, version=sess.get("version"))
check(s == 200 and fech.get("difference") in ("0.00", None),
      f"balconista fecha o caixa contando R$ 2.100,00 (troco 100 + venda 2.000; diferença {fech.get('difference')}) (HTTP {s})")
s, _, _ = B.req("/api/v1/reports/overview")
check(s == 403, f"balconista não abre o relatório da empresa (HTTP {s})")
s, _, _ = B.req(f"/api/v1/inventory/receipts/{rc['id']}")
check(s in (403, 404), f"balconista não vê a entrada com custo (HTTP {s})")

C = A["COORDENADOR_ENTREGAS"]
M = A["MOTORISTA"]
placa = "ENS" + str(secrets.randbelow(10)) + "A" + f"{secrets.randbelow(100):02d}"
s, v, _ = C.req("/api/v1/vehicles", {"plate": placa, "model": "Fiorino", "capacity_quantity": "300",
                                     "capacity_unit": "DUZIA", "odometer_km": "45200"})
check(s == 201, f"coordenador cadastra o veículo {placa} (HTTP {s})")
s, o2, _ = C.req("/api/v1/orders", {"type": "DELIVERY", "customer_id": cli["id"],
                                    "items": [{"product_id": prod, "quantity": "50"}],
                                    "delivery": {"address": "Rua Exemplo, 100 — Centro", "agreed_payment": "CASH"}})
must(s == 201, f"coordenador cria pedido de entrega de 50 dz (HTTP {s})")
s, t, _ = C.req("/api/v1/trips", {})
s, t, _ = C.req(f"/api/v1/trips/{t['id']}/assignments", {"vehicle_id": v.get("id"), "driver_id": contas["MOTORISTA"]["uid"],
                                                         "order_ids": [o2["id"]]}, version=t.get("version"))
check(s == 200, f"coordenador monta a viagem e atribui ao motorista (HTTP {s})")
s, _, _ = M.req("/api/v1/cash-sessions", {"opening_float": "0.00"})
s, t, _ = M.req(f"/api/v1/trips/{t['id']}/depart", {"odometer": "45210"}, version=t.get("version"))
check(s == 200, f"motorista sai para a entrega no app Entregas (HTTP {s})")
stop = next((x["id"] for x in t.get("stops", []) if x.get("order", {}).get("id") == o2["id"]), None)
s, t2, _ = M.req(f"/api/v1/trips/{t['id']}/deliveries", {"stop_id": stop, "recipient_name": "Gerente do mercado",
                                                         "payment": {"parts": [{"method": "CASH", "amount": o2["total"],
                                                                                "presented_cash": o2["total"]}]}})
check(s == 200, f"motorista entrega e recebe R$ {o2['total']} em dinheiro (HTTP {s})")
s, _, _ = M.req(f"/api/v1/orders/{o1['id']}")
check(s in (403, 404), f"motorista não vê pedido do balcão (HTTP {s})")
s, _, _ = M.req("/api/v1/reports/overview")
check(s == 403, f"motorista não abre o relatório (HTTP {s})")

K = A["CONTADOR"]
s, g, _ = K.req("/api/v1/tax-obligations", {"kind": "DAS", "description": "DAS do mês (ensaio)",
                                            "competence": HOJE.strftime("%Y-%m"),
                                            "due_date": (HOJE + timedelta(days=10)).isoformat(), "amount": "850.00"})
must(s == 201, f"contador cria a guia DAS de R$ 850,00 no app Contábil (HTTP {s})")
s, _ = K.upload(f"/api/v1/tax-obligations/{g['id']}/attachments", "das-ensaio.pdf", "application/pdf", PDF)
check(s == 201, f"contador anexa o PDF da guia (HTTP {s})")
s, g, _ = K.req(f"/api/v1/tax-obligations/{g['id']}/submit", {}, version=g.get("version"), idem=False)
check(s == 200, f"contador envia a guia ao dono (HTTP {s})")
s, _, _ = K.req(f"/api/v1/tax-obligations/{g['id']}/approval", {"note": "aprovando a própria guia"}, version=g.get("version"))
check(s == 403, f"contador não aprova a própria guia (HTTP {s})")
s, _, _ = K.req("/api/v1/reports/overview")
check(s == 403, f"contador não abre o resultado da empresa (HTTP {s})")
s, ap, _ = P.req("/api/v1/approvals")
check(any(i.get("id") == g["id"] for i in ap.get("items", [])), "a guia aparece na central de aprovações do patrão")
s, g2, _ = P.req(f"/api/v1/tax-obligations/{g['id']}/approval", {"note": "Pode pagar no vencimento"}, version=g.get("version"))
check(s == 200, f"patrão aprova a guia (HTTP {s})")
s, gv, _ = K.req(f"/api/v1/tax-obligations/{g['id']}")
check(gv.get("state") == "APPROVED" and gv.get("payable_state") == "OPEN", "contador vê APROVADA e ainda NÃO paga")
s, _, _ = P.req(f"/api/v1/tax-obligations/{g['id']}/payments", {"fin_account_id": banco["id"]})
check(s == 200, f"patrão paga a guia pela conta bancária (HTTP {s})")

# ---------------------------------------------------------------- 4. central do patrão e conciliação
print("4) Central do patrão e conciliação do período")
SETOR = {"BALCAO": "COUNTER", "RECEBIMENTO": "COUNTER", "COORDENADOR_ENTREGAS": "DELIVERY", "MOTORISTA": "DELIVERY",
         "CONTADOR": "ACCOUNTING"}
eventos = {}
for setor in sorted(set(SETOR.values())):
    s, ev, _ = P.req(f"/api/v1/audit-events?sector={setor}&limit=100")
    eventos[setor] = ev.get("items", []) if s == 200 else []
for modelo, c in contas.items():
    meus = [e for e in eventos[SETOR[modelo]] if e.get("actor_name") == c["nome"]]
    acoes = sorted({e["action"] for e in meus})
    check(meus and all(e.get("client_id") == c["client"] for e in meus),
          f"central ({SETOR[modelo]}) mostra {len(meus)} movimento(s) de {c['nome']} pelo app {c['client']}: {', '.join(acoes[:6])}")
s, rec, _ = P.req("/api/v1/reports/reconciliation?period=MONTH")
check(s == 200, f"conciliação do mês aberta pelo patrão (HTTP {s})")
for c in rec.get("checks", []):
    check(c["status"] in ("OK", "ROUNDING"),
          f"[{c['area']}] {c['label']}: {c['expected']} × {c['actual']} ({c['status']})")
r = rec.get("report", {})
print(f"  relatório do mês: receita líquida {r.get('net_revenue')} · CMV {r.get('cogs')} · despesas {r.get('expenses_total')}"
      f" · perdas {r.get('losses')} · resultado {r.get('profit')} · {r.get('fulfillments')} entregas")
print("RESULTADO: " + ("PASS" if not falhas else f"FAIL ({len(falhas)})"))
sys.exit(1 if falhas else 0)
