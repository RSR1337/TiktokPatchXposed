package com.golda.patchertiktok;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class LiveStreamsTest {
    private static LiveStreams.Option option(String quality, String flv, int width, int height, String codec) {
        return new LiveStreams.Option(quality, flv, width, height, codec);
    }

    @Test public void listsEveryQualityLargestFirstWithSoundOnlyLast() {
        LiveStreams.Option audio = option("ao", "https://cdn/ao.flv", 0, 0, null);
        LiveStreams.Option sd = option("sd", "https://cdn/sd.flv", 480, 854, "h264");
        LiveStreams.Option origin = option("origin", "https://cdn/origin.flv", 1080, 1920, "bytevc1");
        LiveStreams.Option hd = option("hd", "https://cdn/hd.flv", 720, 1280, "h264");
        LiveStreams.Option fullHd = option("uhd", "https://cdn/uhd.flv", 1080, 1920, "h264");
        List<LiveStreams.Option> choices = LiveStreams.choices(Arrays.asList(audio, sd, origin, hd, fullHd));
        assertEquals(Arrays.asList(fullHd, origin, hd, sd, audio), choices);
        assertSame(fullHd, LiveStreams.best(choices));
    }

    @Test public void dropsRepeatedLinksAndOptionsWithoutOne() {
        LiveStreams.Option hd = option("hd", "https://cdn/hd.flv", 720, 1280, "h264");
        LiveStreams.Option same = option("hd_60", "https://cdn/hd.flv", 720, 1280, "h264");
        LiveStreams.Option none = option("sd", null, 480, 854, "h264");
        assertEquals(Arrays.asList(hd), LiveStreams.choices(Arrays.asList(hd, same, none)));
    }

    @Test public void describesQualityAndCodec() {
        assertEquals("HD 60 · H.264 · FLV", LiveStreams.detail(option("hd_60", "https://cdn/a.flv", 720, 1280, "h264")));
        assertEquals("ORIGIN · H.265 · FLV", LiveStreams.detail(option("origin", "https://cdn/a.flv", 1080, 1920, "bytevc1")));
        assertEquals("FLV", LiveStreams.detail(option("sd", "https://cdn/a.flv", 0, 0, null)));
    }

    @Test public void findsTheFirstFlvLinkInEscapedData() {
        String data = "{\"x\":\"https:\\/\\/cdn.example\\/live\\/a.flv?expire=1\\u0026sign=2\"}";
        assertEquals("https://cdn.example/live/a.flv?expire=1&sign=2", LiveStreams.firstFlv(data));
        assertNull(LiveStreams.firstFlv("{\"hls\":\"https://cdn/a.m3u8\"}"));
    }
}
