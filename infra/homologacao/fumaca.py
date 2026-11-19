#!/usr/bin/env python3
"""Fumaça da HOMOLOGAÇÃO (F16) pela borda TLS, como um navegador: login OIDC do site (PKCE, TOTP do patrão), cookie
Secure/HttpOnly, cabeçalhos de segurança, CSRF, mutação real, IA NÃO CONFIGURADA, logout, conta sem vínculo recusada
e HTTP sem TLS fechado. Só biblioteca padrão. Lê segredos de ~/.config/engenheiro/homologacao (nada é impresso).
Uso: python3 -I infra/homologacao/fumaca.py
"""
import base64, hashlib, hmac, html, http.cookiejar, json, os, re, socket, ssl, struct, sys, time
import urllib.parse, urllib.request

D = os.environ.get("ENGENHEIRO_HOMOLOG_DIR", os.path.expanduser("~/.config/engenheiro/homologacao"))
ENV = dict(l.strip().split("=", 1) for l in open(os.path.join(D, ".env")) if "=" in l and not l.startswith("#"))
DOM, PORTA = ENV["HOMOLOG_DOMINIO"], ENV["HOMOLOG_PORTA"]
SITE = f"https://gestao.{DOM}:{PORTA}"
LOGIN = f"https://login.{DOM}:{PORTA}"
SENHA = ENV["ENGENHEIRO_SENHA_HOMOLOG"]
TOTP_FILE = os.path.join(D, "totp-patrao.txt")
CTX = ssl.create_default_context(cafile=os.path.join(D, "tls", "ca.crt"))
falhas = []


def check(cond, msg):
    print(("  ✓ " if cond else "  ✗ ") + msg)
    if not cond:
        falhas.append(msg)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def browser():
    jar = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect,
                                       urllib.request.HTTPSHandler(context=CTX)), jar


def call(op, url, data=None, headers=None):
    if isinstance(data, dict):
        data = urllib.parse.urlencode(data).encode()
    elif isinstance(data, str):
        data = data.encode()
    h = {"Content-Type": "application/json"} if data and not (headers or {}).get("form") else {}
    h.update({k: v for k, v in (headers or {}).items() if k != "form"})
    if data and (headers or {}).get("form"):
        h["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, headers=h)
    try:
        r = op.open(req, timeout=30)
        return r.status, r.headers, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read().decode(errors="replace")


def totp(secret, t=None):
    # O Keycloak entrega o segredo bruto em totpSecret (a chave HMAC é o próprio texto).
    digest = hmac.new(secret.encode(), struct.pack(">Q", int((t or time.time()) // 30)), hashlib.sha1).digest()
    o = digest[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", digest[o:o + 4])[0] & 0x7FFFFFFF) % 1_000_000)


def form_action(page):
    return html.unescape(re.search(r'<form[^>]*action="([^"]+)"', page).group(1))


def login(username):
    op, jar = browser()
    s, h, _ = call(op, SITE + "/oauth2/authorization/engenheiro")
    assert s == 302, f"autorização HTTP {s}"
    auth = h["Location"]
    q = urllib.parse.parse_qs(urllib.parse.urlparse(auth).query)
    assert auth.startswith(LOGIN) and q.get("code_challenge_method") == ["S256"], "redirect sem HTTPS/PKCE"
    s, h, page = call(op, auth)
    s, h, page = call(op, form_action(page), {"username": username, "password": SENHA, "credentialId": ""},
                      {"form": True})
    for _ in range(6):
        if s == 302 and h["Location"].startswith(SITE + "/login/oauth2/code/"):
            break
        if s == 302:
            s, h, page = call(op, h["Location"])
        elif s == 200 and 'name="totpSecret"' in page:
            secret = html.unescape(re.search(r'name="totpSecret"[^>]*value="([^"]+)"', page).group(1))
            fd = os.open(TOTP_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            os.write(fd, secret.encode()); os.close(fd)
            open(os.path.join(D, ".totp-ultima-janela"), "w").write(str(int(time.time() // 30)))
            s, h, page = call(op, form_action(page), {"totp": totp(secret), "totpSecret": secret,
                                                      "userLabel": "fumaca-homolog", "mode": ""}, {"form": True})
        elif s == 200 and 'name="otp"' in page:
            # O IdP recusa reutilizar o código da mesma janela de 30 s: espera a próxima se já foi usada.
            ultimo = os.path.join(D, ".totp-ultima-janela")
            janela = int(time.time() // 30)
            if os.path.exists(ultimo) and open(ultimo).read().strip() == str(janela):
                time.sleep(30 - time.time() % 30 + 1)
                janela = int(time.time() // 30)
            open(ultimo, "w").write(str(janela))
            s, h, page = call(op, form_action(page), {"otp": totp(open(TOTP_FILE).read().strip()), "login": "Entrar"},
                              {"form": True})
        else:
            t = re.search(r"<title>([^<]*)", page)
            raise AssertionError(f"login {username} parou em HTTP {s}: {t and t.group(1)}")
    s, h, _ = call(op, h["Location"])
    return op, jar, s, h.get("Location")


def cookie(jar, name):
    return next((c for c in jar if c.name == name), None)


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
