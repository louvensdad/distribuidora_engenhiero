#!/usr/bin/env python3
"""Ensaio de falhas na HOMOLOGAÇÃO (F16): IdP fora, banco fora e armazenamento de documentos falhando, com uma
sessão do site em uso. Para cada falha: o que o usuário recebe (erro honesto, sem pilha nem dado), o que NÃO acontece
(nenhum registro pela metade) e a recuperação sozinha quando o serviço volta. Fiscal e LLM: testes automáticos
(F08/F15). Uso: python3 -I infra/operacao/ensaio_falhas.py
"""
import json, os, subprocess, sys, time, uuid
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "homologacao"))
from sessao import SITE, LOGIN, browser, call, check, cookie, falhas, login  # noqa: E402

P = "engenheiro-homolog"


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout.strip()


def dc(*a):
    return sh("docker", "compose", "-p", P, *a)


def esperar(cond, segundos=90):
    for _ in range(segundos):
        try:
            if cond():
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


def q(sql):
    return dc("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "engenheiro", "-Atc", sql)


op, jar, s, _ = login("patrao")
H = {"X-XSRF-TOKEN": cookie(jar, "XSRF-TOKEN").value}
assert call(op, SITE + "/api/v1/me/capabilities")[0] == 200

try:
    print("1) IdP (Keycloak) fora do ar")
    dc("stop", "keycloak")
    s, _, _ = call(op, SITE + "/api/v1/me/capabilities")
    check(s == 200, f"sessão já aberta continua (chaves do IdP em cache na API): HTTP {s}")
    s, h, _ = call(browser()[0], SITE + "/oauth2/authorization/engenheiro")
    s2, _, _ = call(browser()[0], h.get("Location", LOGIN)) if s == 302 else (s, None, None)
    check(s == 302 and s2 in (502, 503, 504), f"novo login: site redireciona ao IdP e a borda responde {s2} (indisponível), sem sucesso falso")
    dc("start", "keycloak")
    check(esperar(lambda: call(browser()[0], LOGIN + "/realms/engenheiro")[0] == 200, 120), "IdP voltou")
    op2, _, s, _ = login("patrao")
    check(s == 302, "login novo volta a funcionar sem reiniciar API nem site")

    print("2) Banco de dados fora do ar")
    docs_antes = q("SELECT count(*) FROM documents")
    dc("stop", "postgres")
    s, h, body = call(op, SITE + "/api/v1/products")
    check(s == 503 and "Exception" not in body and "jdbc" not in body.lower() and "postgres" not in body.lower(),
          f"API responde {s} (problema JSON, rápido) com mensagem genérica (sem pilha, driver ou endereço): {body[:90]}")
    api = sh("docker", "ps", "-q", "--filter", f"name={P}-api")
    ready = sh("docker", "exec", api.split()[0], "sh", "-c", "wget -S -qO- http://127.0.0.1:8080/actuator/health/readiness 2>&1 | head -1")
    check("503" in ready, f"readiness da API cai para DOWN (orquestrador tira de rota): {ready.strip()}")
    dc("start", "postgres")
    check(esperar(lambda: call(op, SITE + "/api/v1/products")[0] == 200, 120),
          "API se reconecta sozinha quando o banco volta (sem reiniciar) e a sessão do site segue válida")

    print("3) Armazenamento de documentos sem gravação")
    for c in api.split():
        sh("docker", "exec", "-u", "0", c, "chmod", "-R", "a-w", "/dados/objetos")
    b = "----f" + uuid.uuid4().hex
    corpo = (f"--{b}\r\nContent-Disposition: form-data; name=\"kind\"\r\n\r\nCONTRATO\r\n--{b}\r\nContent-Disposition: form-data; "
             f"name=\"title\"\r\n\r\nContrato (ensaio de falha)\r\n--{b}\r\nContent-Disposition: form-data; name=\"file\"; "
             f"filename=\"c.pdf\"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.4\n%%EOF\n\r\n--{b}--\r\n").encode()
    s, _, body = call(op, SITE + "/api/v1/documents/uploads", corpo, {**H, "Content-Type": f"multipart/form-data; boundary={b}"})
    check(s == 503, f"envio recusado com 503 (armazenamento indisponível), não 500 nem sucesso: {body[:100]}")
    check(q("SELECT count(*) FROM documents") == docs_antes, "nenhum documento registrado sem o arquivo (sem registro órfão)")
    doc = q("SELECT id FROM documents ORDER BY created_at LIMIT 1")
    if doc:
        s, _, _ = call(op, SITE + f"/api/v1/documents/{doc}/download")
        check(s == 200, f"documentos já gravados continuam baixáveis: HTTP {s}")
    for c in api.split():
        sh("docker", "exec", "-u", "0", c, "chmod", "-R", "u+w", "/dados/objetos")
    s, _, _ = call(op, SITE + "/api/v1/documents/uploads", corpo.replace(b"(ensaio de falha)", b"(depois da falha)"),
                   {**H, "Content-Type": f"multipart/form-data; boundary={b}"})
    check(s == 201, f"armazenamento de volta: envio aceito (HTTP {s})")

finally:
    # Nunca deixa serviço parado ou armazenamento travado, mesmo se o ensaio quebrar no meio.
    dc("start", "keycloak", "postgres")
    for c in sh("docker", "ps", "-q", "--filter", f"name={P}-api").split():
        sh("docker", "exec", "-u", "0", c, "chmod", "-R", "u+w", "/dados/objetos")

print("RESULTADO:", "PASS" if not falhas else f"FAIL ({len(falhas)})")
sys.exit(1 if falhas else 0)
