#!/usr/bin/env python3
"""Verificação da configuração iOS dos 4 apps (roda em Linux; o build iOS em si exige macOS + Xcode).

Confere, para cada app: identificador do pacote e esquema de retorno do login (iguais ao Android e ao client OIDC),
nome exibido, justificativas de câmera e fotos (sem elas o iOS fecha o app ao fotografar), declaração de criptografia,
iPhone + iPad, iOS mínimo, e ícones completos, opacos (a App Store recusa transparência) e diferentes entre os apps.
Uso: python3 -I apps/ios-comum/verificar_ios.py   → código 0 se tudo confere.
"""
import hashlib
import json
import os
import plistlib
import re
import sys

from PIL import Image

APPS = {"mobile-owner": ("br.com.engenheiro.gestao", "Engenheiro Patrão"),
        "mobile-counter": ("br.com.engenheiro.retirada", "Engenheiro Retirada"),
        "mobile-delivery": ("br.com.engenheiro.entregas", "Engenheiro Entregas"),
        "mobile-accountant": ("br.com.engenheiro.contabil", "Engenheiro Contábil")}
falhas = []
icones = {}


def check(ok, msg):
    print(("  ✓ " if ok else "  ✗ ") + msg)
    if not ok:
        falhas.append(msg)


for app, (bundle, nome) in APPS.items():
    print(app)
    ios = f"apps/{app}/ios"
    info = plistlib.load(open(f"{ios}/Runner/Info.plist", "rb"))
    pbx = open(f"{ios}/Runner.xcodeproj/project.pbxproj").read()
    ids = set(re.findall(r"PRODUCT_BUNDLE_IDENTIFIER = ([^;]+);", pbx)) - {bundle + ".RunnerTests"}
    check(ids == {bundle}, f"identificador {bundle} ({', '.join(sorted(ids))})")
    esquemas = [s for t in info.get("CFBundleURLTypes", []) for s in t.get("CFBundleURLSchemes", [])]
    check(esquemas == [bundle], f"retorno do login {bundle}:/oauth2redirect")
    check(info.get("CFBundleDisplayName") == nome, f"nome exibido '{info.get('CFBundleDisplayName')}'")
    for k in ("NSCameraUsageDescription", "NSPhotoLibraryUsageDescription"):
        check(len(info.get(k, "")) >= 20, f"{k} em português")
    check(info.get("ITSAppUsesNonExemptEncryption") is False, "criptografia só padrão declarada (HTTPS)")
    check(not info.get("NSAppTransportSecurity", {}).get("NSAllowsArbitraryLoads"), "sem exceção de HTTP (ATS)")
    alvos = set(re.findall(r'TARGETED_DEVICE_FAMILY = "([^"]+)";', pbx))
    check(alvos == {"1,2"}, "iPhone e iPad")
    minimos = {float(v) for v in re.findall(r"IPHONEOS_DEPLOYMENT_TARGET = ([0-9.]+);", pbx)}
    check(minimos and min(minimos) >= 15.0, f"iOS mínimo {sorted(minimos)}")
    pasta = f"{ios}/Runner/Assets.xcassets/AppIcon.appiconset"
    imgs = json.load(open(f"{pasta}/Contents.json"))["images"]
    ok = True
    for i in imgs:
        im = Image.open(os.path.join(pasta, i["filename"]))
        lado = round(float(i["size"].split("x")[0]) * int(i["scale"].rstrip("x")))
        ok &= im.size == (lado, lado) and im.mode == "RGB"
    check(ok, f"{len(imgs)} ícones no tamanho certo e opacos (iPhone, iPad, App Store)")
    icones[app] = hashlib.sha256(open(f"{pasta}/Icon-App-1024x1024@1x.png", "rb").read()).hexdigest()

check(len(set(icones.values())) == len(APPS), "cada app com o próprio ícone")
print("RESULTADO: " + ("PASS" if not falhas else f"FAIL ({len(falhas)})"))
sys.exit(1 if falhas else 0)
