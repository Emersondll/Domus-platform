package br.com.domus.agent;

import tools.jackson.databind.ObjectMapper;

/** Instância compartilhada de ObjectMapper (Jackson 3: thread-safe após configurada). */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }
}
