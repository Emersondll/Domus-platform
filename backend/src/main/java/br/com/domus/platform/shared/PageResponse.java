package br.com.domus.platform.shared;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Página de resultados exposta pela API.
 *
 * @param items         itens da página atual
 * @param page          índice da página (base 0)
 * @param size          tamanho solicitado
 * @param totalElements total de itens disponíveis
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
