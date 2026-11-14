package com.laiba.rebuilt;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** ElevenLabs text-to-speech. Requests raw 24 kHz 16-bit mono PCM so GeminiTts.play() can play it. */
class ElevenTts {
    static final String DEFAULT_VOICE = "21m00Tcm4TlvDq8ikWAM"; // "Rachel", a premade voice

    private static byte[] readAll(HttpURLConnection c, int code, int limit) throws Exception {
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        if (in == null) return new byte[0];
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) != -1) { out.write(buf, 0, n); if (out.size() > limit) break; }
            return out.toByteArray();
        }
    }

    private static String errorText(byte[] body, int code) {
        String raw = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        try {
            Object d = new JSONObject(raw).opt("detail");
            if (d instanceof JSONObject) return ((JSONObject) d).optString("message", "HTTP " + code);
            if (d != null) return String.valueOf(d);
        } catch (Exception ignored) { }
        return "HTTP " + code + (raw.isEmpty() ? "" : ": " + raw.substring(0, Math.min(raw.length(), 120)));
    }

    static byte[] synth(String key, String voiceId, String model, String text) throws Exception {
        if (!voiceId.matches("[A-Za-z0-9]{10,40}")) throw new Exception("Invalid ElevenLabs voice ID");
        if (!model.matches("[a-z0-9_]+")) throw new Exception("Invalid ElevenLabs model");
        JSONObject body = new JSONObject().put("text", text).put("model_id", model);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/text-to-speech/" + voiceId + "?output_format=pcm_24000").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("xi-api-key", key);
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
            int code = c.getResponseCode();
            byte[] data = readAll(c, code, 24_000_000);
            if (code != 200) throw new Exception(errorText(data, code));
            if (data.length < 2000) throw new Exception("no audio returned");
            return data;
        } finally { if (c != null) c.disconnect(); }
    }

    /** Returns {name, id} pairs of the voices available to this account. */
    static List<String[]> voices(String key) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/voices").openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("xi-api-key", key);
            int code = c.getResponseCode();
            byte[] data = readAll(c, code, 4_000_000);
            if (code != 200) throw new Exception(errorText(data, code));
            JSONArray arr = new JSONObject(new String(data, "UTF-8")).optJSONArray("voices");
            List<String[]> out = new ArrayList<>();
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject v = arr.getJSONObject(i);
                out.add(new String[]{v.optString("name", "?") + (v.optString("category").isEmpty() ? "" : " (" + v.optString("category") + ")"), v.optString("voice_id")});
            }
            return out;
        } finally { if (c != null) c.disconnect(); }
    }
}
