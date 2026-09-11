package io.tinykv;

/**
 * Exceptions thrown by TinyKV during operational or data corruption failures.
 */
public class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class CorruptedRecordException extends StorageException {
        private static final long serialVersionUID = 1L;

        public CorruptedRecordException(String message) {
            super(message);
        }
    }

    public static class StorageClosedException extends StorageException {
        private static final long serialVersionUID = 1L;

        public StorageClosedException(String message) {
            super(message);
        }
    }
}
