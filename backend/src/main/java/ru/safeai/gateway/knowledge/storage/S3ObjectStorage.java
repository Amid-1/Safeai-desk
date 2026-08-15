package ru.safeai.gateway.knowledge.storage;

import io.minio.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;

import java.io.*;

@Component
@ConditionalOnProperty(prefix = "safeai.knowledge.storage", name = "type", havingValue = "s3")
public class S3ObjectStorage implements ObjectStorage {
    private final MinioClient client;
    private final String bucket;

    public S3ObjectStorage(KnowledgeStorageProperties p) throws Exception {
        if (p.endpoint() == null || p.accessKey() == null || p.secretKey() == null)
            throw new IllegalStateException("S3 storage credentials are required");
        client = MinioClient.builder().endpoint(p.endpoint()).credentials(p.accessKey(), p.secretKey()).build();
        bucket = p.bucket();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }

    @Override
    public void put(String key, InputStream content) throws IOException {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(content, -1, 10 * 1024 * 1024).contentType("application/octet-stream").build());
        } catch (Exception e) {
            throw new IOException("S3 put failed", e);
        }
    }

    @Override
    public StoredObject get(String key) throws IOException {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            InputStream stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
            return new StoredObject(new InputStreamResource(stream), stat.size());
        } catch (Exception e) {
            throw new IOException("S3 get failed", e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new IOException("S3 delete failed", e);
        }
    }
}
