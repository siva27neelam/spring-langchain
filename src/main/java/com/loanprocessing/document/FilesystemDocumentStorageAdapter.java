package com.loanprocessing.document;

import com.loanprocessing.support.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class FilesystemDocumentStorageAdapter implements DocumentStoragePort {
    private final Path root;

    public FilesystemDocumentStorageAdapter(@Value("${loan.storage.root}") String root) {
        this.root = Path.of(root);
    }

    @Override
    public void put(String objectKey, byte[] bytes, String mediaType) {
        try {
            Path path = resolve(objectKey);
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new ApiException(503, "OBJECT_STORAGE_UNAVAILABLE", "object storage unavailable");
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try {
            return Files.readAllBytes(resolve(objectKey));
        } catch (IOException e) {
            throw new ApiException(503, "OBJECT_STORAGE_UNAVAILABLE", "object storage unavailable");
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            throw new ApiException(503, "OBJECT_STORAGE_UNAVAILABLE", "object storage unavailable");
        }
    }

    @Override
    public boolean head() {
        try {
            Files.createDirectories(root);
            Path probe = root.resolve(".health");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public List<Stored> list() {
        if (!Files.exists(root)) {
            return List.of();
        }
        List<Stored> stored = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String key = root.relativize(path).toString().replace('\\', '/');
                    if (".health".equals(key)) {
                        return;
                    }
                    stored.add(new Stored(key, Files.getLastModifiedTime(path).toInstant()));
                } catch (IOException ignored) {
                    // skip unreadable entries; the reconciler will try again
                }
            });
        } catch (IOException e) {
            return List.of();
        }
        return stored;
    }

    private Path resolve(String objectKey) {
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root.normalize())) {
            throw new ApiException(422, "VALIDATION", "object key is invalid");
        }
        return path;
    }
}
