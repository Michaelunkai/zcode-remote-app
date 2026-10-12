package com.michaelunkai.zcoderemote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Pure JUnit 4 tests for the static {@link UrlStore#normalize(String)} contract:
 * trim; empty -> null; prepend https:// when no scheme; scheme must be http/https
 * and host must contain a dot, otherwise null.
 */
public class UrlStoreTest {

    @Test
    public void normalize_nullInput_returnsNull() {
        assertNull(UrlStore.normalize(null));
    }

    @Test
    public void normalize_emptyString_returnsNull() {
        assertNull(UrlStore.normalize(""));
    }

    @Test
    public void normalize_whitespaceOnlyString_returnsNull() {
        assertNull(UrlStore.normalize("   "));
    }

    @Test
    public void normalize_bareDomain_prependsHttpsScheme() {
        assertEquals("https://example.com", UrlStore.normalize("example.com"));
    }

    @Test
    public void normalize_paddedHttpsUrl_trimsAndKeepsValue() {
        assertEquals("https://a.b/c?x=1", UrlStore.normalize("  https://a.b/c?x=1  "));
    }

    @Test
    public void normalize_httpUrlWithPath_keptAsIs() {
        assertEquals("http://host.org/path", UrlStore.normalize("http://host.org/path"));
    }

    @Test
    public void normalize_ftpScheme_returnsNull() {
        assertNull(UrlStore.normalize("ftp://files.net"));
    }

    @Test
    public void normalize_plainTextWithoutDotHost_returnsNull() {
        assertNull(UrlStore.normalize("not a url"));
    }

    @Test
    public void normalize_fullSessionUrlWithQuery_keptAsIs() {
        assertEquals("https://zcode.z.ai/remote/v4?sid=x",
                UrlStore.normalize("https://zcode.z.ai/remote/v4?sid=x"));
    }
}
