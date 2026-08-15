package ru.safeai.gateway.knowledge.storage;

import java.io.IOException;
import java.io.InputStream;

public interface ObjectStorage {
    void put(String key, InputStream content) throws IOException;

    StoredObject get(String key) throws IOException;

    void delete(String key) throws IOException;
}
