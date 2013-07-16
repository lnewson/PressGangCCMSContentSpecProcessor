package org.jboss.pressgang.ccms.contentspec.processor.exceptions;

public class ProcessingException extends Exception {
    private static final long serialVersionUID = 5545431739360420849L;

    public ProcessingException() {
    }

    public ProcessingException(final String message) {
        super(message);
    }

    public ProcessingException(final Throwable cause) {
        super(cause);
    }

    public ProcessingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
