package com.dovideo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioExtractionServiceTest {

    @Test
    void shouldFormatTimeRangesForShortAndLongVideos() {
        assertEquals("00:00", AudioExtractionService.formatTime(0));
        assertEquals("00:28", AudioExtractionService.formatTime(28));
        assertEquals("05:00", AudioExtractionService.formatTime(300));
        assertEquals("10:00", AudioExtractionService.formatTime(600));
        assertEquals("11:12", AudioExtractionService.formatTime(672));
        assertEquals("01:05:00", AudioExtractionService.formatTime(3900));
    }
}
