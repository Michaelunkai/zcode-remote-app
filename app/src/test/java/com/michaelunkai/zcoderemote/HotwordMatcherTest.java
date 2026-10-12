package com.michaelunkai.zcoderemote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure JUnit 4 tests for the "hey zcode" wake-phrase matcher. */
public class HotwordMatcherTest {

    @Test
    public void matches_exactPhrase() {
        assertTrue(HotwordMatcher.matches("hey zcode"));
    }

    @Test
    public void matches_recognizerMangling() {
        assertTrue(HotwordMatcher.matches("hey z code"));
        assertTrue(HotwordMatcher.matches("OK Z Coat, open the session"));
        assertTrue(HotwordMatcher.matches("Hey... Zcod!"));
        assertTrue(HotwordMatcher.matches("hi  zcote"));
    }

    @Test
    public void matches_insideLongerUtterance() {
        assertTrue(HotwordMatcher.matches("hey zcode open my session now"));
    }

    @Test
    public void matches_caseAndPunctuationInsensitive() {
        assertTrue(HotwordMatcher.matches("HEY ZCODE!"));
        assertTrue(HotwordMatcher.matches("He-zcode?"));
    }

    @Test
    public void noMatch_ordinarySpeech() {
        assertFalse(HotwordMatcher.matches("open the code"));
        assertFalse(HotwordMatcher.matches("hey google"));
        assertFalse(HotwordMatcher.matches("the zebra crossed the road"));
        assertFalse(HotwordMatcher.matches(""));
        assertFalse(HotwordMatcher.matches(null));
        assertFalse(HotwordMatcher.matches("z"));
        assertFalse(HotwordMatcher.matches("code"));
    }

    @Test
    public void normalize_stripsNonAlphanumericAndCollapsesSpace() {
        assertEquals("hey zcode open session", HotwordMatcher.normalize("  Hey,  ZCODE!! open... session "));
    }
}
