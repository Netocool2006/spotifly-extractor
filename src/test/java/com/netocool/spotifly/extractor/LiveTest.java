package com.netocool.spotifly.extractor;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import java.io.File;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;

/**
 * Against YouTube itself, before a module is published: a module that no
 * longer finds, reads or downloads songs must never reach the phones.
 * Runs only with {@code -PliveYouTube} (the workflow passes it).
 */
public class LiveTest {
    @Test
    public void findsReadsAndDownloadsASong() throws Exception {
        try {
            liveCheck();
        } catch (SignInConfirmNotBotException blocked) {
            // YouTube asks GitHub's data-centre runners to confirm they are
            // not a bot, whatever the module. That says nothing of it; the
            // app sets aside a module that fails where its own copy works.
            assumeNoException("YouTube blocked this machine as a bot", blocked);
        }
    }

    private void liveCheck() throws Exception {
        JSONArray songs = new JSONArray(Extractor.search("Queen Bohemian Rhapsody", true, 3));
        assertTrue("YouTube Music found no songs", songs.length() > 0);
        String url = songs.getJSONObject(0).getString("url");

        JSONObject details = new JSONObject(Extractor.details(url));
        assertTrue("no duration in " + details, details.optLong("duration") > 60);

        File song = File.createTempFile("song", ".m4a");
        song.delete();
        try {
            Extractor.download(url, song.getPath());
            assertTrue("the download is too small: " + song.length(), song.length() > 500_000);
        } finally {
            song.delete();
        }

        JSONArray videos = new JSONArray(Extractor.search("Queen Bohemian Rhapsody", false, 5));
        assertTrue("YouTube found no videos", videos.length() > 0);
    }
}
