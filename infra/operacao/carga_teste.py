#!/usr/bin/env python3
"""Carga proporcional na HOMOLOGAÇÃO (F16): W usuários simultâneos SEM pausa (muito acima do piloto, ~15 pessoas com
pausas humanas) por D segundos, pela borda TLS, misturando leituras pesadas (relatório canônico, listas, histórico,
estoque) e escritas concorrentes (pedidos que reservam estoque, com chave idempotente). No fim confere a invariante
de estoque: reservado = pedidos aceitos e físico intacto. Saída: latências p50/p95/p99 por operação e erros.
uso: python3 -I infra/operacao/carga_teste.py [usuarios=20] [segundos=60]
"""
import json, os, random, sys, threading, time, urllib.request, uuid
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "homologacao"))
from sessao import CTX, NoRedirect, SITE, call, cookie, login  # noqa: E402

W = int(sys.argv[1]) if len(sys.argv) > 1 else 20
D = int(sys.argv[2]) if len(sys.argv) > 2 else 60
op0, jar, s, _ = login("patrao")
assert s == 302
H = {"X-XSRF-TOKEN": cookie(jar, "XSRF-TOKEN").value}
tag = time.strftime("%H%M%S")


def post(op, path, body, extra=None):
    s, h, b = call(op, SITE + path, json.dumps(body), {**H, **(extra or {})})
    assert s in (200, 201), f"{path} {s} {b[:160]}"
    return json.loads(b), h


k = lambda: {"Idempotency-Key": "carga-" + str(uuid.uuid4())}
p, h = post(op0, "/api/v1/products", {"sku": f"CARGA-{tag}", "name": "Cana (teste de carga)", "base_unit": "DUZIA",
                                      "base_unit_confirmed": True})
post(op0, f"/api/v1/products/{p['id']}/prices", {"price": "7.50"}, {"If-Match": h["ETag"]})
sup, _ = post(op0, "/api/v1/suppliers", {"name": f"Fornecedor carga {tag}"})
r, _ = post(op0, "/api/v1/inventory/receipts", {"supplier_id": sup["id"], "lines": [
    {"product_id": p["id"], "quantity_received": "100000", "unit_cost": "3.40"}]}, k())
post(op0, f"/api/v1/inventory/receipts/{r['id']}/confirm", None, {**k(), "If-Match": f"\"{r['version']}\""})
cli, _ = post(op0, "/api/v1/customers", {"name": f"Cliente carga {tag}"})

OPS = [("relatorio", 30, "GET", "/api/v1/reports/overview?period=MONTH"),
       ("pedidos", 20, "GET", "/api/v1/orders?limit=25"),
       ("historico", 10, "GET", "/api/v1/audit-events?limit=25"),
       ("estoque", 10, "GET", "/api/v1/inventory/balances?limit=25"),
       ("novo_pedido", 30, "POST", "/api/v1/orders")]
peso = [o[1] for o in OPS]
lat = {o[0]: [] for o in OPS}
erros = {}
aceitos = [0]
lock = threading.Lock()
fim = time.time() + D


def worker():
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect,
                                     urllib.request.HTTPSHandler(context=CTX))
    while time.time() < fim:
        nome, _, metodo, path = random.choices(OPS, peso)[0]
        t = time.perf_counter()
        if metodo == "GET":
            s, _, _ = call(op, SITE + path)
            ok = s == 200
        else:
            s, _, _ = call(op, SITE + path, json.dumps({"type": "PICKUP", "customer_id": cli["id"],
                                                       "items": [{"product_id": p["id"], "quantity": "1"}]}), {**H, **k()})
            ok = s == 201
        ms = (time.perf_counter() - t) * 1000
        with lock:
            lat[nome].append(ms)
            if ok and metodo == "POST":
                aceitos[0] += 1
            if not ok:
                erros[f"{nome}:{s}"] = erros.get(f"{nome}:{s}", 0) + 1


ts = [threading.Thread(target=worker) for _ in range(W)]
t0 = time.time()
[t.start() for t in ts]
[t.join() for t in ts]
dur = time.time() - t0


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q * len(v)))] if v else 0


total = sum(len(v) for v in lat.values())
print(f"carga: {W} usuários simultâneos sem pausa, {dur:.0f} s, {total} requisições ({total / dur:.1f}/s), erros: {erros or 'nenhum'}")
print(f"{'operação':<12} {'n':>6} {'p50 ms':>8} {'p95 ms':>8} {'p99 ms':>8} {'máx ms':>8}")
for nome, v in lat.items():
    print(f"{nome:<12} {len(v):>6} {pct(v, .5):>8.0f} {pct(v, .95):>8.0f} {pct(v, .99):>8.0f} {max(v or [0]):>8.0f}")
todos = [x for v in lat.values() for x in v]
p95 = pct(todos, .95)
s, _, b = call(op0, SITE + f"/api/v1/inventory/balances?q=CARGA-{tag}")
bal = json.loads(b)["items"][0]
reservado, fisico = float(bal["reserved"]), float(bal["physical"])
inv_ok = abs(reservado - aceitos[0]) < 1e-9 and abs(fisico - 100000) < 1e-9
print(f"estoque: físico {bal['physical']} · reservado {bal['reserved']} · pedidos aceitos {aceitos[0]} → "
      f"{'✓ invariante confere' if inv_ok else '✗ DIVERGÊNCIA'}")
ok = not erros and inv_ok and p95 < 1500
print(f"RESULTADO: {'PASS' if ok else 'FAIL'} (critério: 0 erro, invariante de estoque, p95 geral < 1500 ms; p95 = {p95:.0f} ms)")
sys.exit(0 if ok else 1)
