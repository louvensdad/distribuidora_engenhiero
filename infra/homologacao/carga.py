#!/usr/bin/env python3
"""Carga EXPLÍCITA de dados fictícios de homologação (F16), pelo site, com a identidade do patrão: produto com preço,
fornecedor, cliente, despesa aprovada e dois documentos (arquivos reais no armazenamento). Serve para o ensaio de
backup/restauração ter banco E objetos. Nunca roda por migration. Uso: python3 -I infra/homologacao/carga.py
"""
import json, os, sys, time, uuid
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sessao import SITE, call, cookie, login  # noqa: E402

op, jar, s, _ = login("patrao")
assert s == 302, f"login HTTP {s}"
xsrf = cookie(jar, "XSRF-TOKEN").value
H = {"X-XSRF-TOKEN": xsrf}
tag = time.strftime("%Y%m%d%H%M%S")


def post(path, body, extra=None):
    s, h, b = call(op, SITE + path, json.dumps(body), {**H, **(extra or {})})
    assert s in (200, 201), f"{path} HTTP {s}: {b[:200]}"
    return json.loads(b), h


def idem():
    return {"Idempotency-Key": "carga-" + str(uuid.uuid4())}


p, h = post("/api/v1/products", {"sku": f"CANA-{tag}", "name": "Cana caiana (homologação)", "base_unit": "DUZIA",
                                  "base_unit_confirmed": True})
post(f"/api/v1/products/{p['id']}/prices", {"price": "7.50"}, {"If-Match": h["ETag"]})
post("/api/v1/suppliers", {"name": f"Sítio Exemplo {tag}"})
def cpf_ficticio():
    import random
    d = [random.randint(0, 9) for _ in range(9)]
    for n in (10, 11):
        v = sum(a * b for a, b in zip(d, range(n, 1, -1))) * 10 % 11
        d.append(0 if v == 10 else v)
    return "".join(map(str, d))


post("/api/v1/customers", {"name": f"Mercado Fictício {tag}", "address": "Rua Exemplo, 100", "personal_document": cpf_ficticio()})
e, _ = post("/api/v1/expenses", {"category": "ESTRUTURA", "subcategory": "Aluguel", "description": "Aluguel (fictício)",
                                 "amount": "1500.00", "competence": time.strftime("%Y-%m-01")}, idem())
post(f"/api/v1/expenses/{e['id']}/approve", {}, {**idem(), "If-Match": f"\"{e['version']}\""})


def upload(kind, title, name, ctype, data):
    b = "----fronteira" + uuid.uuid4().hex
    parts = []
    for k, v in (("kind", kind), ("title", title), ("competence", time.strftime("%Y-%m"))):
        parts.append(f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode())
    parts.append(f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\nContent-Type: {ctype}\r\n\r\n"
                 .encode() + data + b"\r\n")
    parts.append(f"--{b}--\r\n".encode())
    s, _, body = call(op, SITE + "/api/v1/documents/uploads", b"".join(parts),
                      {**H, "Content-Type": f"multipart/form-data; boundary={b}"})
    assert s == 201, f"upload HTTP {s}: {body[:200]}"
    return json.loads(body)


pdf = (b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj "
       b"3 0 obj<</Type/Page/MediaBox[0 0 200 200]/Parent 2 0 R>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n")
d1 = upload("CONTRATO", f"Contrato de homologação {tag}", "contrato.pdf", "application/pdf", pdf)
import struct, zlib  # noqa: E402


def png(w=8, h=8):
    raw = b"".join(b"\x00" + bytes([40, 160, 90]) * w for _ in range(h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


d2 = upload("COMPROVANTE", f"Comprovante fictício {tag}", "comprovante.png", "image/png", png())
print(f"carga ok: produto {p['sku']}, despesa aprovada, documentos {d1['id'][:8]}… e {d2['id'][:8]}…")
