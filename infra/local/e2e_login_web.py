#!/usr/bin/env python3
"""Fumaça ponta a ponta do login do site (BFF) contra o Keycloak LOCAL real.

Fluxo: API /oauth2/authorization -> formulário do Keycloak -> (TOTP do patrão) -> callback da API ->
sessão -> /api/v1/me/capabilities -> mutação com CSRF -> logout. Também verifica que o contador
não entra no site do patrão. Usa apenas a biblioteca padrão. Senha via ENGENHEIRO_SENHA_LOCAL.
"""
import base64, hashlib, hmac, html, http.cookiejar, json, os, re, struct, sys, time
import urllib.parse, urllib.request

API = os.environ.get("ENGENHEIRO_API", "http://localhost:8081")
SENHA = os.environ["ENGENHEIRO_SENHA_LOCAL"]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


class LocalhostPolicy(http.cookiejar.DefaultCookiePolicy):
    """Keycloak marca cookies como Secure; em http://localhost o navegador real os aceita, o cookiejar não."""

    def return_ok_secure(self, cookie, request):
        return urllib.parse.urlparse(request.get_full_url()).hostname == "localhost" or \
            super().return_ok_secure(cookie, request)


def browser():
    jar = http.cookiejar.CookieJar(policy=LocalhostPolicy())
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect)
    return op, jar


def call(op, url, data=None, headers=None, method=None):
    body = None
    h = dict(headers or {})
    if isinstance(data, dict):
        body = urllib.parse.urlencode(data).encode()
        h.setdefault("Content-Type", "application/x-www-form-urlencoded")
    elif isinstance(data, str):
        body = data.encode()
        h.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        r = op.open(req)
        return r.status, dict(r.headers), r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()


def totp(secret_raw: str, t=None):
    counter = int((t or time.time()) // 30)
    digest = hmac.new(secret_raw.encode(), struct.pack(">Q", counter), hashlib.sha1).digest()
    o = digest[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", digest[o:o + 4])[0] & 0x7FFFFFFF) % 1_000_000)


def form_action(page):
    m = re.search(r'<form[^>]*action="([^"]+)"', page)
    return html.unescape(m.group(1))


def login(username):
    op, jar = browser()
    s, h, _ = call(op, API + "/oauth2/authorization/engenheiro")
    assert s == 302, s
    auth_url = h["Location"]
    q = urllib.parse.parse_qs(urllib.parse.urlparse(auth_url).query)
    assert q["code_challenge_method"] == ["S256"] and "code_challenge" in q, "PKCE ausente"
    s, h, page = call(op, auth_url)
    s, h, page = call(op, form_action(page), {"username": username, "password": SENHA, "credentialId": ""})
    for _ in range(5):  # etapas internas do IdP (ações obrigatórias)
        if s == 302 and h["Location"].startswith(API + "/login/oauth2/code/"):
            break
        if s == 302:
            s, h, page = call(op, h["Location"])
        elif s == 200 and 'name="totpSecret"' in page:
            secret = html.unescape(re.search(r'name="totpSecret"[^>]*value="([^"]+)"', page).group(1))
            s, h, page = call(op, form_action(page), {"totp": totp(secret), "totpSecret": secret,
                                                      "userLabel": "e2e-local", "mode": ""})
        else:
            titulo = re.search(r"<title>([^<]*)", page)
            erro = re.search(r'id="input-error[^>]*>\s*([^<]+)', page)
            raise AssertionError(f"login {username} parou em HTTP {s}: {titulo and titulo.group(1)} {erro and erro.group(1).strip()}")
    assert s == 302 and h["Location"].startswith(API + "/login/oauth2/code/"), "IdP não devolveu o código"
    s, h, _ = call(op, h["Location"])  # callback da API
    return op, jar, s, h.get("Location")


def cookie(jar, name):
    return next((c for c in jar if c.name == name), None)



op, jar, s, loc = login("patrao")
print(f"patrao: callback HTTP {s} -> {loc}")
sess = cookie(jar, "ENGENHEIRO_SESSION")
print(f"cookie de sessão: HttpOnly={sess.has_nonstandard_attr('HttpOnly')} "
           f"SameSite={sess.get_nonstandard_attr('SameSite')}")
api_cookies = [c for c in jar if not c.path.startswith("/realms/")]  # os do Keycloak são do IdP
print("cookies da API: " + ", ".join(c.name for c in api_cookies)
      + " | algum contém JWT: " + str(any(c.value.startswith("eyJ") for c in api_cookies)))
s, _, body = call(op, API + "/api/v1/me/capabilities")
me = json.loads(body)
print(f"GET /api/v1/me/capabilities: HTTP {s} client={me['client_id']} admin={me['admin']} "
           f"empresa='{me['organization']['name']}' capacidades={len(me['capabilities'])}")
s, _, body = call(op, API + "/bff/session")
print(f"GET /bff/session: HTTP {s} {body}")
xsrf = cookie(jar, "XSRF-TOKEN").value
alvo = "/api/v1/users/0193a000-0000-7000-8000-000000000a06/access-policy"
s, h, body = call(op, API + alvo)
etag = h.get("ETag")
payload = json.dumps({"template": "MOTORISTA", "reason": "e2e local: reaplicar modelo"})
s1, _, _ = call(op, API + alvo, payload, {"If-Match": etag})
s2, _, _ = call(op, API + alvo, payload, {"If-Match": etag, "X-XSRF-TOKEN": xsrf})
print(f"POST access-policy sem CSRF: HTTP {s1} | com CSRF: HTTP {s2}")
s, _, body = call(op, API + "/bff/logout", "{}", {"X-XSRF-TOKEN": xsrf})
print(f"POST /bff/logout: HTTP {s} logout_url aponta ao Keycloak="
           f"{json.loads(body)['logout_url'].startswith('http://localhost:8180/realms/engenheiro/')}")
s, _, _ = call(op, API + "/api/v1/me/capabilities")
print(f"após logout /api/v1/me/capabilities: HTTP {s}")

op, jar, s, loc = login("contador")
s, _, body = call(op, API + "/api/v1/me/capabilities")
print(f"contador no site do patrão: HTTP {s} {json.loads(body)['code']}")

