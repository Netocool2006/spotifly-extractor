package com.netocool.spotifly.extractor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

/**
 * Songs from YouTube on Android, where yt-dlp cannot run: the searches, the
 * details checked before a download, and the download itself, through
 * NewPipe Extractor.
 *
 * This class and NewPipe make up the extractor module. The APK carries one
 * copy; a newer one, built when NewPipe releases, comes signed from GitHub
 * (.github/workflows/extractor.yml) and is loaded in its place by
 * {@code com.netocool.spotifly.YouTube}, which calls these methods by name.
 * Keep their names and signatures: every published module must answer them.
 */
public final class Extractor {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
    /** YouTube serves its audio in pieces; bigger requests are throttled. */
    private static final int PIECE = 1 << 20;
    private static final int TIMEOUT_MS = 30_000;

    /** The details read last, so the download that follows them reuses their links. */
    private static final Map<String, StreamInfo> READ = Collections.synchronizedMap(
            new LinkedHashMap<String, StreamInfo>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StreamInfo> eldest) {
                    return size() > 8;
                }
            });

    static {
        NewPipe.init(new Http());
    }

    private Extractor() {}

    /**
     * The module's build number, which updates compare: the workflow's run
     * number for a published module, 0 for the APK's own copy.
     */
    public static int build() {
        return BuildInfo.BUILD;
    }

    /** The NewPipe Extractor release inside. */
    public static String newPipe() {
        return BuildInfo.NEWPIPE;
    }

    private static StreamingService service() {
        return ServiceList.YouTube;
    }

    /**
     * Up to {@code limit} results for {@code query}, from YouTube Music's
     * songs when {@code music}, else from YouTube's videos, as a JSON array
     * of objects with url, title, channel, uploader, duration and
     * view_count, the fields yt-dlp's flat search gives.
     */
    public static String search(String query, boolean music, int limit) throws Exception {
        List<String> filter = Collections.singletonList(music
                ? YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
                : YoutubeSearchQueryHandlerFactory.VIDEOS);
        SearchInfo found = SearchInfo.getInfo(
                service(), service().getSearchQHFactory().fromQuery(query, filter, ""));
        JSONArray results = new JSONArray();
        for (InfoItem item : found.getRelatedItems()) {
            if (!(item instanceof StreamInfoItem) || results.length() >= limit) {
                continue;
            }
            StreamInfoItem song = (StreamInfoItem) item;
            JSONObject result = new JSONObject();
            result.put("url", song.getUrl());
            result.put("title", song.getName());
            result.put("channel", song.getUploaderName());
            result.put("uploader", song.getUploaderName());
            if (song.getDuration() > 0) {
                result.put("duration", song.getDuration());
            }
            if (song.getViewCount() >= 0) {
                result.put("view_count", song.getViewCount());
            }
            results.put(result);
        }
        return results.toString();
    }

    /**
     * What YouTube says of a video, as a JSON object with id, title,
     * channel, uploader, duration and webpage_url: what the Rust side checks
     * against Spotify's song before downloading it.
     */
    public static String details(String url) throws Exception {
        StreamInfo info = StreamInfo.getInfo(service(), url);
        READ.put(url, info);
        JSONObject details = new JSONObject();
        details.put("id", info.getId());
        details.put("title", info.getName());
        details.put("channel", info.getUploaderName());
        details.put("uploader", info.getUploaderName());
        if (info.getDuration() > 0) {
            details.put("duration", info.getDuration());
        }
        details.put("webpage_url", info.getUrl());
        return details.toString();
    }

    /**
     * Saves the video's AAC audio (M4A, 44.1 kHz, what the player decodes)
     * at {@code path}. It is written beside it first and moved in place
     * once complete, so a stopped download never looks like a song.
     */
    public static void download(String url, String path) throws Exception {
        StreamInfo info = READ.remove(url);
        if (info == null) {
            info = StreamInfo.getInfo(service(), url);
        }
        AudioStream audio = bestM4a(info);
        if (audio == null) {
            throw new IOException("no M4A audio for " + url);
        }
        long size = audio.getItagItem() != null ? audio.getItagItem().getContentLength() : -1;
        String link = audio.getContent();
        File part = new File(path + ".part");
        long got = 0;
        int request = 0;
        int renewed = 0;
        try (OutputStream out = new FileOutputStream(part)) {
            while (size <= 0 || got < size) {
                long end = got + PIECE - 1;
                if (size > 0) {
                    end = Math.min(end, size - 1);
                }
                byte[] piece;
                try {
                    piece = piece(link + "&range=" + got + "-" + end + "&rn=" + (request++));
                } catch (Refused refused) {
                    // The link expired or was refused: a fresh one, resumed
                    // where this one stopped.
                    if (renewed++ >= 2) {
                        throw refused;
                    }
                    StreamInfo again = StreamInfo.getInfo(service(), url);
                    AudioStream same = null;
                    for (AudioStream a : again.getAudioStreams()) {
                        if (a.getItag() == audio.getItag()) {
                            same = a;
                        }
                    }
                    if (same == null) {
                        throw refused;
                    }
                    link = same.getContent();
                    continue;
                }
                if (piece.length == 0) {
                    break;
                }
                out.write(piece);
                got += piece.length;
                if (size <= 0 && piece.length < PIECE) {
                    break;
                }
            }
        }
        if (got == 0 || !part.renameTo(new File(path))) {
            part.delete();
            throw new IOException("the download of " + url + " did not complete");
        }
    }

    private static AudioStream bestM4a(StreamInfo info) {
        AudioStream best = null;
        for (AudioStream a : info.getAudioStreams()) {
            if (a.getFormat() == null || !"m4a".equals(a.getFormat().getSuffix())) {
                continue;
            }
            if (best == null || a.getAverageBitrate() > best.getAverageBitrate()) {
                best = a;
            }
        }
        return best;
    }

    /** A refused piece, which a fresh link may still serve. */
    private static final class Refused extends IOException {
        Refused(int status) {
            super("YouTube answered HTTP " + status);
        }
    }

    private static byte[] piece(String link) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(link).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setDoOutput(true);
            try (OutputStream body = connection.getOutputStream()) {
                // The body YouTube's own web player sends with each piece.
                body.write(new byte[] {0x78, 0});
            }
            int status = connection.getResponseCode();
            if (status >= 400) {
                throw new Refused(status);
            }
            try (InputStream in = connection.getInputStream()) {
                return readAll(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) > 0; ) {
            all.write(buffer, 0, n);
        }
        return all.toByteArray();
    }

    /** NewPipe's requests, over Android's own HTTP client. */
    private static final class Http extends Downloader {
        @Override
        public Response execute(Request request) throws IOException {
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(request.url()).openConnection();
            try {
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod(request.httpMethod());
                connection.setRequestProperty("User-Agent", USER_AGENT);
                for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                    for (String value : header.getValue()) {
                        connection.addRequestProperty(header.getKey(), value);
                    }
                }
                byte[] body = request.dataToSend();
                if (body != null) {
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body);
                    }
                }
                int status = connection.getResponseCode();
                InputStream in = status >= 400
                        ? connection.getErrorStream()
                        : connection.getInputStream();
                String text = "";
                if (in != null) {
                    try (InputStream stream = in) {
                        text = new String(readAll(stream), StandardCharsets.UTF_8);
                    }
                }
                Map<String, List<String>> headers = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> header :
                        connection.getHeaderFields().entrySet()) {
                    if (header.getKey() != null) {
                        headers.put(header.getKey(), header.getValue());
                    }
                }
                return new Response(status, connection.getResponseMessage(), headers, text,
                        connection.getURL().toString());
            } finally {
                connection.disconnect();
            }
        }
    }
}
