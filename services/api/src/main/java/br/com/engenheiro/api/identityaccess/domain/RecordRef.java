package br.com.engenheiro.api.identityaccess.domain;

import java.util.UUID;

/** Atributos de um registro necessários para avaliar escopo. Lidos do banco, nunca do cliente. */
public record RecordRef(UUID organizationId, UUID branchId, Sector sector, UUID createdBy, UUID assignedTo) {
}
