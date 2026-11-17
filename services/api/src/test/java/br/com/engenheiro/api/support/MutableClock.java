package br.com.engenheiro.api.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Relógio dos testes: segue o sistema; um teste pode fixar um instante (fronteira de dia) e sempre volta com reset(). */
public final class MutableClock extends Clock {
    private final AtomicReference<Instant> fixed = new AtomicReference<>();

    public void set(Instant instant) {
        fixed.set(instant);
    }

    public void reset() {
        fixed.set(null);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        Instant f = fixed.get();
        return f == null ? Instant.now() : f;
    }
}
