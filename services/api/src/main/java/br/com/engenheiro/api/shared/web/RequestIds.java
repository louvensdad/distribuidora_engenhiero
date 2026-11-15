package br.com.engenheiro.api.shared.web;

import org.slf4j.MDC;

public final class RequestIds {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";

    private RequestIds() {
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "sem-request-id" : id;
    }
}
