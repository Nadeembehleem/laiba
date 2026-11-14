package com.laiba.rebuilt;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Natural-sounding speech from Gemini TTS (24 kHz 16-bit mono PCM). Falls back to Android TTS on any failure. */
class GeminiTts {
    static volatile AudioTrack track;
    static volatile boolean cancelled;

    static byte[] synth(String key, String model, String voice, String text) throws Exception {
        if (!model.matches("[a-zA-Z0-9._-]+")) throw new Exception("Invalid TTS model name");
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", text)))))
                .put("generationConfig", new JSONObject()
                        .put("responseModalities", new JSONArray().put("AUDIO"))
                        .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                                .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice)))));
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
            int code = c.getResponseCode();
            String raw = Llm.read(c, code);
            if (code != 200) throw new Exception("HTTP " + code + ": " + Llm.detail(raw));
            JSONArray parts = new JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            for (int i = 0; i < parts.length(); i++) {
                JSONObject inline = parts.getJSONObject(i).optJSONObject("inlineData");
                if (inline == null) inline = parts.getJSONObject(i).optJSONObject("inline_data");
                if (inline != null) return Base64.decode(inline.getString("data"), Base64.DEFAULT);
            }
            throw new Exception("no audio returned");
        } finally { if (c != null) c.disconnect(); }
    }

    static long durationMs(byte[] pcm) { return pcm.length / 48L; } // 24000 Hz * 2 bytes = 48 bytes per ms

    /** Plays PCM and blocks until finished or stop() is called. */
    static void play(byte[] pcm) throws Exception {
        cancelled = false;
        int frames = pcm.length / 2;
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length)
                .setTransferMode(AudioTrack.MODE_STATIC).build();
        track = t;
        try {
            t.write(pcm, 0, pcm.length);
            t.play();
            long end = System.currentTimeMillis() + durationMs(pcm) + 2500;
            while (!cancelled && t.getPlaybackHeadPosition() < frames && System.currentTimeMillis() < end) Thread.sleep(40);
        } finally {
            try { t.stop(); } catch (Exception ignored) { }
            t.release();
            track = null;
        }
    }

    static void stop() { cancelled = true; }
}
