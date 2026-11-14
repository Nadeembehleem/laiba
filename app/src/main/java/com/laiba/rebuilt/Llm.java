package com.laiba.rebuilt;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/** Minimal client for Google Gemini (native) and OpenAI-compatible chat APIs (DeepSeek, OpenAI, custom). */
class Llm {
    static class Msg {
        final String role; // "user" or "assistant"
        final String text;
        final String img;  // optional base64 JPEG
        Msg(String role, String text, String img) { this.role = role; this.text = text; this.img = img; }
    }

    static class Fail extends Exception {
        final int code;
        Fail(int code, String m) { super(m); this.code = code; }
    }

    final boolean gemini;
    final String base, model, key;
    final boolean grounding;

    Llm(SharedPreferences p) {
        gemini = "3".equals(p.getString("provider", "0"));
        key = p.getString("key", "").trim();
        model = p.getString("model", gemini ? "gemini-2.5-flash" : "deepseek-chat").trim();
        base = p.getString("endpoint", gemini ? "https://generativelanguage.googleapis.com/v1beta" : "https://api.deepseek.com")
                .trim().replaceAll("/+$", "");
        grounding = "true".equals(p.getString("grounding", "true"));
    }

    boolean ready() { return !key.isEmpty(); }

    /** Sends the conversation and returns the model's text. Retries transient 503s. useSearch is Gemini-only. */
    String complete(String system, List<Msg> msgs, boolean useSearch) throws Exception {
        if (!base.startsWith("https://")) throw new Exception("Only HTTPS API endpoints are allowed");
        if (!model.matches("[a-zA-Z0-9._-]+")) throw new Exception("Invalid model name");
        Fail last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return once(system, msgs, useSearch && gemini && grounding);
            } catch (Fail f) {
                last = f;
                if (f.code == 400 && useSearch && gemini && grounding) { // model may not support search tool
                    try { return once(system, msgs, false); } catch (Fail f2) { throw f2; }
                }
                if (f.code != 503 || attempt == 2) throw f;
                Thread.sleep((1L << attempt) * 2000L);
            }
        }
        throw last;
    }

    private String once(String system, List<Msg> msgs, boolean search) throws Exception {
        JSONObject payload = new JSONObject();
        String url;
        if (gemini) {
            url = base + "/models/" + model + ":generateContent";
            JSONArray contents = new JSONArray();
            for (Msg m : msgs) {
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("text", m.text));
                if (m.img != null) parts.put(new JSONObject().put("inlineData",
                        new JSONObject().put("mimeType", "image/jpeg").put("data", m.img)));
                contents.put(new JSONObject().put("role", m.role.equals("assistant") ? "model" : "user").put("parts", parts));
            }
            payload.put("contents", contents);
            payload.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
            if (search) payload.put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));
        } else {
            url = base.endsWith("/chat/completions") ? base : (base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions");
            payload.put("model", model);
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("role", "system").put("content", system));
            for (Msg m : msgs) {
                if (m.img != null) {
                    JSONArray c = new JSONArray();
                    c.put(new JSONObject().put("type", "text").put("text", m.text));
                    c.put(new JSONObject().put("type", "image_url").put("image_url",
                            new JSONObject().put("url", "data:image/jpeg;base64," + m.img)));
                    arr.put(new JSONObject().put("role", m.role).put("content", c));
                } else arr.put(new JSONObject().put("role", m.role).put("content", m.text));
            }
            payload.put("messages", arr);
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (gemini) c.setRequestProperty("x-goog-api-key", key);
            else c.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream out = c.getOutputStream()) { out.write(payload.toString().getBytes("UTF-8")); }
            int code = c.getResponseCode();
            String raw = read(c, code);
            if (code >= 400) {
                String d = detail(raw);
                if (code == 429) throw new Fail(code, "Quota or rate limit reached. " + d);
                if (code == 503) throw new Fail(code, "Server temporarily overloaded. " + d);
                if (code == 404) throw new Fail(code, "Model not found. Pick one in Settings. " + d);
                if (code == 401 || code == 403) throw new Fail(code, "Check API key and permissions. " + d);
                throw new Fail(code, "HTTP " + code + ": " + d);
            }
            JSONObject r = new JSONObject(raw);
            String answer;
            if (gemini) {
                JSONArray cand = r.optJSONArray("candidates");
                if (cand == null || cand.length() == 0) throw new Exception("Gemini returned no answer (possibly filtered)");
                JSONObject content = cand.getJSONObject(0).optJSONObject("content");
                if (content == null) throw new Exception("Gemini returned an empty answer");
                JSONArray parts = content.optJSONArray("parts");
                StringBuilder sb = new StringBuilder();
                if (parts != null) for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
                answer = sb.toString().trim();
            } else {
                answer = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
            }
            if (answer.isEmpty()) throw new Exception("Model returned an empty response");
            return answer;
        } finally { if (c != null) c.disconnect(); }
    }

    static String read(HttpURLConnection c, int code) throws Exception {
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        if (in == null) return "";
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > 2_000_000) break;
            }
            return out.toString("UTF-8");
        }
    }

    static String detail(String raw) {
        try { return new JSONObject(raw).getJSONObject("error").optString("message", ""); }
        catch (Exception e) { return raw.substring(0, Math.min(raw.length(), 180)); }
    }
}
