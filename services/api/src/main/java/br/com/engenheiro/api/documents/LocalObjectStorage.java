package br.com.engenheiro.api.documents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pasta privada no servidor (desenvolvimento/instância única), fora de qualquer diretório servido.
 * Sem `engenheiro.storage.local-dir` fica NÃO CONFIGURADO. Produção: adaptador S3-compatível privado (F16).
 */
@Component
public class LocalObjectStorage implements ObjectStorage {
    private final Path root;

    public LocalObjectStorage(@Value("${engenheiro.storage.local-dir:}") String dir) {
        this.root = dir == null || dir.isBlank() ? null : Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public boolean configured() {
        return root != null;
    }

    private Path resolve(String key) {
        if (!key.matches("[a-z]+/[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("chave de objeto inválida");
        }
        return root.resolve(key).normalize();
    }

    @Override
    public void put(String key, byte[] bytes) {
        try {
            Path p = resolve(key);
            Files.createDirectories(p.getParent());
            for (Path dir = p.getParent(); dir != null && dir.startsWith(root); dir = dir.getParent()) {
                try {
                    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
                } catch (UnsupportedOperationException ignored) {
                    break;
                }
            }
            Files.write(p, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // sistema sem POSIX
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            Path p = resolve(key);
            return Files.exists(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
