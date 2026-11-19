# Inventário de artefatos, versões, suporte e acesso do titular

Estado em 19/11/2026 (F17). Os artefatos ficam **fora do repositório** (`~/.local/state/engenheiro/artefatos/` na
máquina de build); aqui ficam a identificação e os checksums.

## Versões

| Componente | Commit | Identificação | Onde |
|---|---|---|---|
| API (imagem) | `a79ef78fa59f` | `engenheiro-api:a79ef78fa59f` · imagem `sha256:678cbd90a953…` · jar `sha256:193475eb9bd1…` | No ar na homologação; manifesto `artefatos/a79ef78fa59f.json` |
| Site (imagem) | `a79ef78fa59f` | `engenheiro-site:a79ef78fa59f` · imagem `sha256:7114bb3b1e1d…` | No ar na homologação |
| 4 apps Android (homologação) | `dd2bec9fd5b8` | versão 0.1.0, build 51, assinados com a chave de **homologação** (cert. SHA-256 `52:AD:35:3A:…:6D:97`) | `artefatos/apps-homologacao-dd2bec9fd5b8/` (manifesto.json + SHA256SUMS) |

O código da API e do site não mudou entre `a79ef78` e `dd2bec9` (só documentação e roteiros). Pipeline completo
(`infra/ci/verificar.sh`) PASS em `dd2bec9`: 18 portões, API com 199 testes e 0 falhas.

### Checksums dos APKs (SHA-256)

```
25a1004dac5786a816621461f95396a6063144a095cc6106a0236039a4466cc8  br.com.engenheiro.gestao-0.1.0+51-homologacao.apk
06a7cbe34bc7736dd2e54da41618e65482f0e79d1239984b67eba1006b9980fd  br.com.engenheiro.retirada-0.1.0+51-homologacao.apk
15e6ddc07ab95c0f76a3bbb6cf6d18412077d3e94ce782b24b43432a6f56313a  br.com.engenheiro.entregas-0.1.0+51-homologacao.apk
8ee62f58971e6a8f6022f016b77dda4d077f2de3280f8e52724175611e44cf9c  br.com.engenheiro.contabil-0.1.0+51-homologacao.apk
```

Estes APKs são de **homologação** (CA de teste embutida). Produção exige novo build com a chave do titular e as URLs
de produção (pendência A4); iOS não foi produzido.

## O que o titular precisa ter em mãos (acesso)

| Item | Hoje | Em produção deve ficar com |
|---|---|---|
| Conta de dono no sistema (site + app Patrão) com TOTP | Homologação: `patrao` | Titular; TOTP no celular dele e códigos de recuperação guardados |
| Administração do IdP (Keycloak, realm `master`) | Senha no `.env` da homologação; só por dentro do servidor | Cofre do titular; uso só pelo responsável técnico |
| Segredos do servidor (banco, IdP, provisionamento, pepper de log) | `.env` da homologação (600, fora do repositório) | Cofre (D09) |
| Senha do backup | `.env` da homologação | Cofre, **separada** do destino do backup |
| Chave de assinatura Android | Só a de homologação | Play App Signing do titular (D07) |
| Domínio e certificado TLS | `homolog.localtest.me` com CA de teste | Domínio do titular com certificado público |
| Repositório do código | Este repositório (`main`) | Conta do titular, com o histórico completo |

## Documentos da entrega

- Guias por perfil: `docs/entrega/GUIAS_POR_PERFIL.md`
- Suporte e operação assistida: `docs/entrega/SUPORTE_E_OPERACAO_ASSISTIDA.md`
- Runbooks: `docs/runbooks/` (implantação e rollback, backup e restauração, incidentes)
- Aceite: `docs/aceite/MATRIZ_CENARIOS.md`, `TERMO_DE_ACEITE.md`, `PENDENCIAS.md`
- Decisões: `docs/adr/` (ADR-030 implantação, ADR-031 aceite)
