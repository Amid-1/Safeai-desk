package ru.safeai.gateway.knowledge.storage;

import org.springframework.core.io.Resource;

public record StoredObject(Resource resource, long contentLength) {
}
