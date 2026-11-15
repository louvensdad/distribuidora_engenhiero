package br.com.engenheiro.api.shared.ids;

import java.security.SecureRandom;
import java.util.UUID;

/** Gera UUIDv7 (RFC 9562) no servidor: ordenável por tempo, sem expor sequência. */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
