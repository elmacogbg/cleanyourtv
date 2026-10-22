package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class NewsTest {

    private static final String RSS = "<?xml version=\"1.0\"?><rss><channel><title>SVT Nyheter</title>"
            + "<item><title><![CDATA[Regeringen & oppositionen enas]]></title></item>"
            + "<item><title>Storm &quot;Babet&quot; n&#229;r V&#xE4;stkusten</title></item>"
            + "<item><title>  </title></item>"
            + "<item><title>Storm &quot;Babet&quot; n&#229;r V&#xE4;stkusten</title></item>"
            + "</channel></rss>";

    @Test
    public void titlesSkipChannelBlankAndDuplicates() {
        List<String> t = News.titles(RSS, 20);
        assertEquals(Arrays.asList("Regeringen & oppositionen enas", "Storm \"Babet\" når Västkusten"), t);
    }

    @Test
    public void maxIsRespected() {
        assertEquals(1, News.titles(RSS, 1).size());
    }

    @Test
    public void tickerJoinsWithBullets() {
        assertEquals("A     •     B", News.tickerLine(Arrays.asList("A", "B")));
    }
}
