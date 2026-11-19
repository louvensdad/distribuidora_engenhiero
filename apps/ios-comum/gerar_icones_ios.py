#!/usr/bin/env python3
"""Ícones iOS de cada app (iPhone e iPad) a partir do ícone do pacote de design (docs/especificacao/assets/apps).

A App Store recusa ícone com transparência e o iOS aplica a própria máscara arredondada: os cantos transparentes da
fonte são preenchidos com a cor de fundo do próprio ícone (pixel da borda), gerando um quadrado opaco sem emenda.
Uso: python3 -I apps/ios-comum/gerar_icones_ios.py   (na raiz do repositório)
"""
import json
import os

from PIL import Image

APPS = {"mobile-owner": "patrao", "mobile-counter": "retirada", "mobile-delivery": "entregas",
        "mobile-accountant": "contador"}

for app, nome in APPS.items():
    src = Image.open(f"docs/especificacao/assets/apps/engenheiro-{nome}.png").convert("RGBA")
    fundo = Image.new("RGBA", src.size, src.getpixel((src.width // 2, 4))[:3] + (255,))
    base = Image.alpha_composite(fundo, src).convert("RGB")
    pasta = f"apps/{app}/ios/Runner/Assets.xcassets/AppIcon.appiconset"
    cont = json.load(open(os.path.join(pasta, "Contents.json")))
    for img in cont["images"]:
        lado = round(float(img["size"].split("x")[0]) * int(img["scale"].rstrip("x")))
        base.resize((lado, lado), Image.LANCZOS).save(os.path.join(pasta, img["filename"]), optimize=True)
    print(f"{app}: {len(cont['images'])} ícones a partir de engenheiro-{nome}.png")
