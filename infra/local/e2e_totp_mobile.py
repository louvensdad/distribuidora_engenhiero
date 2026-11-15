# Cadastro do TOTP do patrão na Custom Tab do emulador via DevTools remoto (adb forward tcp:9333 localabstract:chrome_devtools_remote).
# Requer o pacote websockets. Uso: python3 infra/local/e2e_totp_mobile.py
import asyncio, json, sys, time, hmac, hashlib, struct, urllib.request
import websockets

def totp(secret_raw, t=None):
    c = int((t or time.time()) // 30)
    d = hmac.new(secret_raw.encode(), struct.pack(">Q", c), hashlib.sha1).digest()
    o = d[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", d[o:o + 4])[0] & 0x7FFFFFFF) % 1_000_000)

async def main():
    pages = json.load(urllib.request.urlopen("http://localhost:9333/json"))
    ws_url = next(p["webSocketDebuggerUrl"] for p in pages if "CONFIGURE_TOTP" in p["url"])
    async with websockets.connect(ws_url, max_size=None) as ws:
        n = 0
        async def ev(expr):
            nonlocal n; n += 1
            await ws.send(json.dumps({"id": n, "method": "Runtime.evaluate", "params": {"expression": expr, "returnByValue": True}}))
            while True:
                m = json.loads(await ws.recv())
                if m.get("id") == n: return m["result"]["result"].get("value")
        secret = await ev("document.querySelector('[name=totpSecret]').value")
        code = totp(secret)
        r = await ev(f"""(()=>{{const f=document.querySelector('[name=totpSecret]').form;
            f.querySelector('#totp').value='{code}';
            const l=f.querySelector('#userLabel'); if(l) l.value='Emulador F02';
            f.submit(); return 'enviado';}})()""")
        print(r, "(segredo lido:", "sim" if secret else "não", ")")
asyncio.run(main())
