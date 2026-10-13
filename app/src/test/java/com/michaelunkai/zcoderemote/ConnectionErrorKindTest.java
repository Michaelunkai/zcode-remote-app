package com.michaelunkai.zcoderemote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** JVM-pure classification tests: engine (net::ERR_*) + framework wordings. */
public class ConnectionErrorKindTest {

    @Test
    public void chromiumConnectionRefusedIsUnreachable() {
        assertEquals(ConnectionErrorKind.UNREACHABLE,
                ConnectionErrorKind.classify("net::ERR_CONNECTION_REFUSED"));
    }

    @Test
    public void chromiumTimedOutIsUnreachable() {
        assertEquals(ConnectionErrorKind.UNREACHABLE,
                ConnectionErrorKind.classify("net::ERR_CONNECTION_TIMED_OUT"));
    }

    @Test
    public void chromiumNameNotResolvedIsUnreachable() {
        assertEquals(ConnectionErrorKind.UNREACHABLE,
                ConnectionErrorKind.classify("net::ERR_NAME_NOT_RESOLVED"));
    }

    @Test
    public void chromiumInternetDisconnectedIsUnreachable() {
        assertEquals(ConnectionErrorKind.UNREACHABLE,
                ConnectionErrorKind.classify("net::ERR_INTERNET_DISCONNECTED"));
    }

    @Test
    public void frameworkConnectionUnsuccessfulIsUnreachable() {
        assertEquals(ConnectionErrorKind.UNREACHABLE,
                ConnectionErrorKind.classify("The connection to the server was unsuccessful."));
    }

    @Test
    public void frameworkUnknownHostIsUnreachable() {
        assertEquals(ConnectionErrorKind.UNREACHABLE,
                ConnectionErrorKind.classify("UnknownHostException: unreachable.pc"));
    }

    @Test
    public void unknownEngineErrorIsOther() {
        assertEquals(ConnectionErrorKind.OTHER,
                ConnectionErrorKind.classify("net::ERR_UNKNOWN"));
    }

    @Test
    public void nullDescriptionIsOther() {
        assertEquals(ConnectionErrorKind.OTHER, ConnectionErrorKind.classify(null));
    }
}
