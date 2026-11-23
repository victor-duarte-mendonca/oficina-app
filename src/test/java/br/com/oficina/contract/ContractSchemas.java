package br.com.oficina.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Carrega os schemas de {@code contracts/} (raiz do repositório) para os testes de contrato. */
final class ContractSchemas {

    static final Path DIRECTORY = Path.of("contracts").toAbsolutePath();
    private static final String BASE_ID = "https://oficina.local/contracts/";
    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
        builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(BASE_ID, DIRECTORY.toUri().toString())));

    private ContractSchemas() {
    }

    static JsonSchema schema(String name) {
        return FACTORY.getSchema(SchemaLocation.of(BASE_ID + name + ".schema.json"));
    }

    static Set<ValidationMessage> validate(String schemaName, JsonNode message) {
        return schema(schemaName).validate(message);
    }

    static JsonNode read(String relativePath) {
        try {
            return MAPPER.readTree(Files.readString(DIRECTORY.resolve(relativePath)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
