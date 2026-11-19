#!/usr/bin/env python3
"""Fumaça da HOMOLOGAÇÃO (F16) pela borda TLS, como um navegador: login OIDC do site (PKCE, TOTP do patrão), cookie
Secure/HttpOnly, cabeçalhos de segurança, CSRF, mutação real, IA NÃO CONFIGURADA, logout, conta sem vínculo recusada
e só a borda publicando porta. Uso: python3 -I infra/homologacao/fumaca.py
"""
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sessao import *  # noqa: F401,F403
from sessao import falhas

print(f"Homologação {SITE}")
s, h, body = call(browser()[0], SITE + "/")
check(s == 200 and '<div id="root"' in body, "site responde por HTTPS (HTTP 200)")
check("default-src 'self'" in (h.get("Content-Security-Policy") or ""), "CSP restrita à própria origem")
check(h.get("Strict-Transport-Security", "").startswith("max-age="), "HSTS na borda")
check(h.get("X-Frame-Options") == "DENY" and h.get("X-Content-Type-Options") == "nosniff", "sem moldura e nosniff")
check("nginx/" not in (h.get("Server") or ""), "versão do servidor não exposta")
s, _, versao = call(browser()[0], SITE + "/versao.json")
check(s == 200 and "commit" in versao, f"artefato identificado: {versao.strip()}")
import subprocess
portas = subprocess.run(["docker", "compose", "-p", "engenheiro-homolog", "ps", "--format", "{{.Service}} {{.Ports}}"],
                        capture_output=True, text=True).stdout
publicas = [l for l in portas.splitlines() if "->" in l]
check(len(publicas) == 1 and publicas[0].startswith("borda") and "8443->8443" in publicas[0]
      and ":80->" not in publicas[0], "só a borda TLS publica porta (8443); banco, IdP, API e site só na rede interna")

op, jar, s, loc = login("patrao")
check(s == 302 and loc == SITE + "/", f"login do patrão (PKCE + TOTP) volta ao site: HTTP {s}")
sess = cookie(jar, "ENGENHEIRO_SESSION")
check(sess is not None and sess.secure and sess.has_nonstandard_attr("HttpOnly"), "cookie de sessão Secure e HttpOnly")
check(not any(c.value.startswith("eyJ") for c in jar if c.domain.startswith("gestao")), "nenhum token no navegador")
s, h, body = call(op, SITE + "/api/v1/me/capabilities")
me = json.loads(body)
check(s == 200 and me["admin"] and me["client_id"] == "engenheiro-web", f"/me: admin={me.get('admin')} empresa="
      f"'{me['organization']['name']}' (bootstrap do banco vazio)")
check(bool(h.get("X-Request-Id")), "X-Request-Id devolvido (correlação)")
xsrf = cookie(jar, "XSRF-TOKEN").value
produto = json.dumps({"sku": "HOMOLOG-" + str(int(time.time())), "name": "Cana de homologação", "base_unit": "DUZIA",
                      "base_unit_confirmed": True})
s1, _, _ = call(op, SITE + "/api/v1/products", produto)
s2, _, body = call(op, SITE + "/api/v1/products", produto, {"X-XSRF-TOKEN": xsrf})
check(s1 == 403 and s2 == 201, f"mutação sem CSRF recusada ({s1}) e com CSRF aceita ({s2})")
s, _, body = call(op, SITE + "/api/v1/ai/status")
check(s == 200 and json.loads(body)["state"] == "NOT_CONFIGURED", "IA sem chave: NOT_CONFIGURED")
s, _, body = call(op, SITE + "/bff/logout", "{}", {"X-XSRF-TOKEN": xsrf})
check(s == 200 and json.loads(body)["logout_url"].startswith(LOGIN + "/realms/engenheiro/"), "logout aponta ao IdP")
s, _, _ = call(op, SITE + "/api/v1/me/capabilities")
check(s == 401, f"depois do logout a API recusa (HTTP {s})")

op, jar, s, loc = login("contador")
s, _, body = call(op, SITE + "/api/v1/me/capabilities")
check(s in (401, 403), f"conta do IdP sem vínculo no banco não entra (HTTP {s})")

print("RESULTADO:", "PASS" if not falhas else f"FAIL ({len(falhas)})")
sys.exit(1 if falhas else 0)
