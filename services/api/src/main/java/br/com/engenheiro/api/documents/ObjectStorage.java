package br.com.engenheiro.api.documents;

import java.util.Optional;

/** Armazenamento privado de bytes de anexos (ADR-003). Nunca público; acesso só pela API após autorização. */
public interface ObjectStorage {
    /** false = NÃO CONFIGURADO: uploads respondem 503, sem sucesso simulado. */
    boolean configured();

    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);

    void delete(String key);
}
