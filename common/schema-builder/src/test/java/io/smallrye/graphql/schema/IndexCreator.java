package io.smallrye.graphql.schema;

import java.io.*;

import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;

public class IndexCreator {
    public static Index index(Class<?>... clazzes) {
        final Indexer indexer = new Indexer();
        for (Class<?> clazz : clazzes) {
            InputStream stream = IndexCreator.class.getClassLoader()
                    .getResourceAsStream(clazz.getName().replace('.', '/') + ".class");
            try {
                indexer.index(stream);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return indexer.complete();
    }
}
