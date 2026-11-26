package com.wdcdp.cdpscreen.cdp;

/** A DevTools protocol call failed, or the connection went away. */
public class CdpException extends RuntimeException {

    public CdpException(String message) {
        super(message);
    }

    public CdpException(String message, Throwable cause) {
        super(message, cause);
    }
}
