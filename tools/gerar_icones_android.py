#!/usr/bin/env python3
"""Gera ic_launcher.png nas densidades Android a partir das artes 1024 px do pacote.

Uso: python3 -I tools/gerar_icones_android.py
A arte-base é proposta do pacote (docs/especificacao/assets/apps); adaptive icon com área segura fica para a F11–F14.
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ARTES = ROOT / "docs/especificacao/assets/apps"
APPS = {
    "mobile-owner": "engenheiro-patrao.png",
    "mobile-counter": "engenheiro-retirada.png",
    "mobile-delivery": "engenheiro-entregas.png",
    "mobile-accountant": "engenheiro-contador.png",
}
DENSIDADES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

for app, arte in APPS.items():
    base = Image.open(ARTES / arte).convert("RGBA")
    for dens, px in DENSIDADES.items():
        out = ROOT / f"apps/{app}/android/app/src/main/res/mipmap-{dens}/ic_launcher.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        base.resize((px, px), Image.Resampling.LANCZOS).save(out, optimize=True)
    print(f"{app}: ícones gerados de {arte}")
