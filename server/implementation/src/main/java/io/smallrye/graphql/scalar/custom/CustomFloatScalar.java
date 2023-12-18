package io.smallrye.graphql.scalar.custom;

import java.lang.reflect.Type;
import java.math.BigDecimal;

import jakarta.json.JsonValue.ValueType;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.bind.serializer.JsonbSerializer;
import jakarta.json.bind.serializer.SerializationContext;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

import io.smallrye.graphql.spi.ClassloadingService;

/**
 * A base class for all CustomScalars that are based on GraphQL's Float.
 */
public interface CustomFloatScalar {
    // Note: using lambdas for the SERIALIZER/DESERIALIZER instances doesn't work because it
    // hides the parameterized type from Jsonb.

    /**
     * A serializer for CustomScalars based on a GraphQL Float, to inform JsonB how to serialize
     * a CustomStringScalar to a BigDecimal value.
     */
    JsonbSerializer<CustomFloatScalar> SERIALIZER = new JsonbSerializer<>() {
        @Override
        public void serialize(CustomFloatScalar customFloatScalar, JsonGenerator jsonGenerator,
                SerializationContext serializationContext) {
            jsonGenerator.write(customFloatScalar.floatValueForSerialization());
        }
    };

    /**
     * A deserializer for CustomScalars based on a GraphQL Float, to inform JsonB how to deserialize
     * to an instance of a CustomFloatScalar.
     */
    JsonbDeserializer<CustomFloatScalar> DESERIALIZER = new JsonbDeserializer<>() {
        @Override
        public CustomFloatScalar deserialize(JsonParser jsonParser,
                DeserializationContext deserializationContext, Type type) {
            ClassloadingService classloadingService = ClassloadingService.get();
            try {
                if (jsonParser.getValue().getValueType() == ValueType.NULL) {
                    return null;
                } else {
                    return (CustomFloatScalar) classloadingService.loadClass(type.getTypeName())
                            .getConstructor(BigDecimal.class)
                            .newInstance(jsonParser.getBigDecimal());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    };

    BigDecimal floatValueForSerialization();
}
