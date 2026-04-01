package com.asm.delivery.storage;

public class StorageException extends RuntimeException {

    public StorageException(String path, Throwable cause) {
        super("Failed to store file: " + path, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
