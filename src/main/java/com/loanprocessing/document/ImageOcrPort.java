package com.loanprocessing.document;

public interface ImageOcrPort {
    String read(byte[] image, String mediaType);
}
