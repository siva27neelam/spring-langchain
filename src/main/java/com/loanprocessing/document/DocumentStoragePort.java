package com.loanprocessing.document;

import java.time.Instant;
import java.util.List;

public interface DocumentStoragePort {
    void put(String objectKey, byte[] bytes, String mediaType);

    byte[] get(String objectKey);

    void delete(String objectKey);

    boolean head();

    List<Stored> list();

    record Stored(String objectKey, Instant modifiedAt) {
    }
}
