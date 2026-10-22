package br.com.domus.platform.devices.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Persiste a configuração (mapa de strings, sem segredos) como JSON em texto. */
@Converter
public class ConfigConverter implements AttributeConverter<Map<String, String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        return MAPPER.writeValueAsString(attribute == null ? Map.of() : attribute);
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.readValue(dbData, new TypeReference<LinkedHashMap<String, String>>() { });
    }
}
