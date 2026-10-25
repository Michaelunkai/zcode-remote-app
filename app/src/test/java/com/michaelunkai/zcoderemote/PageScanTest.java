package com.michaelunkai.zcoderemote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** JVM-pure: only classify(String) is tested — never android.webkit types. */
public class PageScanTest {

    @Test
    public void classifyStripsQuotesAndKeepsKnownStates() {
        assertEquals(PageScan.INVALID, PageScan.classify("\"invalid\""));
        assertEquals(PageScan.CONNECTING, PageScan.classify("\"connecting\""));
        assertEquals(PageScan.MENU, PageScan.classify("\"menu\""));
        assertEquals(PageScan.DESKTOP_OFFLINE, PageScan.classify("\"desktop_offline\""));
        assertEquals(PageScan.DESKTOP_TIMEDOUT, PageScan.classify("\"desktop_timedout\""));
        assertEquals(PageScan.NOSESSION, PageScan.classify("\"nosession\""));
        assertEquals(PageScan.OK, PageScan.classify("\"ok\""));
        assertEquals(PageScan.OK, PageScan.classify("ok"));
    }

    @Test
    public void classifySplitsAwayTheDiagnosticPath() {
        assertEquals(PageScan.NOSESSION, PageScan.classify("\"nosession|/auth/login\""));
        assertEquals(PageScan.MENU, PageScan.classify("\"menu|/\""));
    }

    @Test
    public void classifyRejectsUnknownAndGarbage() {
        assertEquals("", PageScan.classify(null));
        assertEquals("", PageScan.classify("\"undefined\""));
        assertEquals("", PageScan.classify("{\"result\":null}"));
        assertEquals("", PageScan.classify("4"));
    }
}
