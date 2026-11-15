package br.com.engenheiro.api.identityaccess.domain;

/** Escopos de registro. Não são hierárquicos: ASSIGNED não equivale a "todos do setor". */
public enum Scope {
    OWN, ASSIGNED, SECTOR, BRANCH, ORGANIZATION
}
