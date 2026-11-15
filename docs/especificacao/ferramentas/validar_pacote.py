from pathlib import Path
from decimal import Decimal
import hashlib,json,xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
requirements=json.loads((root/'plano/requisitos.json').read_text())
phases=json.loads((root/'plano/fases.json').read_text())
screens=json.loads((root/'design/telas.json').read_text())
assert len({r['id'] for r in requirements})==len(requirements)
for phase in phases:
 assert (root/phase['prompt']).is_file(), phase['prompt']
 assert all(any(p['id']==dep for p in phases) for dep in phase['dependencies'])
 assert phase['status']=='NOT_STARTED'
for screen in screens:
 assert (root/screen['asset']).is_file(),screen['asset']
for path in root.rglob('*.svg'):ET.parse(path)
case=json.loads((root/'testes/dados-financeiros.json').read_text())['cases'][0]
spent=sum(Decimal(v) for v in case['expense_categories'].values())
profit=Decimal(case['revenue'])-Decimal(case['cogs'])-spent-Decimal(case['inventory_losses'])
assert profit==Decimal(case['expected']['profit'])
assert spent==Decimal(case['expected']['expenses_total'])
for line in (root/'SHA256SUMS.txt').read_text().splitlines():
 digest,name=line.split('  ',1)
 assert hashlib.sha256((root/name).read_bytes()).hexdigest()==digest,name
print('PASS:',len(requirements),'requisitos;',len(phases),'fases;',len(screens),'telas; SVGs, links estruturados e checksums verificados.')
