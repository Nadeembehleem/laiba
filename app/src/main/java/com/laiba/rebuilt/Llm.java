package com.laiba.rebuilt;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** AI client with automatic fail-over: tries the active provider first, then every other provider that has a saved key. */
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

    static class Cfg {
        final String id, name, base, model, key;
        final boolean gemini, vision;
        Cfg(String id, String name, String base, String model, String key, boolean gemini, boolean vision) {
            this.id = id; this.name = name; this.base = base; this.model = model; this.key = key; this.gemini = gemini; this.vision = vision;
        }
    }

    final ArrayList<Cfg> chain = new ArrayList<>();
    final boolean grounding;
    volatile String used = "";

    Llm(SharedPreferences p) {
        grounding = "true".equals(p.getString("grounding", "true"));
        String active = Providers.activeId(p);
        add(p, active);
        if ("true".equals(p.getString("fallback", "true")))
            for (Providers.P q : Providers.ALL) if (!q.id.equals(active)) add(p, q.id);
    }

    private void add(SharedPreferences p, String id) {
        Providers.P q = Providers.find(id);
        if (q == null) return;
        String k = Providers.key(p, id);
        if (k.isEmpty()) return;
        chain.add(new Cfg(id, q.name.replaceAll(" \\(.*", ""), Providers.base(p, id), Providers.model(p, id), k, q.gemini, q.vision));
    }

    boolean ready() { return !chain.isEmpty(); }

    static String chatUrl(String base) {
        base = base.trim().replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) return base;
        if (base.matches(".*/(v\\d+|inference)$")) return base + "/chat/completions";
        return base + "/v1/chat/completions";
    }

    static String modelsUrl(String base) { return chatUrl(base).replace("/chat/completions", "/models"); }

    /** Sends the conversation and returns the model's text. Falls over to the next provider on any failure. */
    String complete(String system, List<Msg> msgs, boolean useSearch) throws Exception {
        boolean hasImg = false;
        for (Msg m : msgs) if (m.img != null) hasImg = true;
        StringBuilder errors = new StringBuilder();
        for (Cfg c : chain) {
            if (hasImg && !c.vision) continue;
            try {
                String r = attempt(c, system, msgs, useSearch && c.gemini && grounding);
                used = c.name;
                return r;
            } catch (Exception e) {
                if (errors.length() > 0) errors.append("\n");
                errors.append(c.name).append(": ").append(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        if (errors.length() > 0) throw new Exception(errors.toString());
        if (hasImg) throw new Exception("None of your saved providers can read images. Add a free Gemini key in Settings.");
        throw new Exception("No API key saved. Open Settings and add one.");
    }

    private String attempt(Cfg c, String system, List<Msg> msgs, boolean search) throws Exception {
        if (!c.base.startsWith("https://")) throw new Exception("Only HTTPS API endpoints are allowed");
        if (!c.model.matches(c.gemini ? "[a-zA-Z0-9._-]+" : "[a-zA-Z0-9._:/-]+")) throw new Exception("Invalid model name");
        int tries = chain.size() > 1 ? 1 : 3;
        Fail last = null;
        for (int i = 0; i < tries; i++) {
            try {
                return once(c, system, msgs, search);
            } catch (Fail f) {
                last = f;
                if (f.code == 400 && search) { return once(c, system, msgs, false); } // model may not support the search tool
                if (f.code != 503 || i == tries - 1) throw f;
                Thread.sleep((1L << i) * 2000L);
            }
        }
        throw last;
    }

    private String once(Cfg c, String system, List<Msg> msgs, boolean search) throws Exception {
        JSONObject payload = new JSONObject();
        String url;
        if (c.gemini) {
            url = c.base.replaceAll("/+$", "") + "/models/" + c.model + ":generateContent";
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
            url = chatUrl(c.base);
            payload.put("model", c.model);
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("role", "system").put("content", system));
            for (Msg m : msgs) {
                if (m.img != null) {
                    JSONArray parts = new JSONArray();
                    parts.put(new JSONObject().put("type", "text").put("text", m.text));
                    parts.put(new JSONObject().put("type", "image_url").put("image_url",
                            new JSONObject().put("url", "data:image/jpeg;base64," + m.img)));
                    arr.put(new JSONObject().put("role", m.role).put("content", parts));
                } else arr.put(new JSONObject().put("role", m.role).put("content", m.text));
            }
            payload.put("messages", arr);
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (c.gemini) conn.setRequestProperty("x-goog-api-key", c.key);
            else conn.setRequestProperty("Authorization", "Bearer " + c.key);
            try (OutputStream out = conn.getOutputStream()) { out.write(payload.toString().getBytes("UTF-8")); }
            int code = conn.getResponseCode();
            String raw = read(conn, code);
            if (code >= 400) {
                String d = detail(raw);
                if (code == 429) throw new Fail(code, "limit reached. " + d);
                if (code == 503) throw new Fail(code, "server busy. " + d);
                if (code == 404) throw new Fail(code, "model not found, pick another in Settings. " + d);
                if (code == 401 || code == 403) throw new Fail(code, "check the API key. " + d);
                throw new Fail(code, "HTTP " + code + ": " + d);
            }
            JSONObject r = new JSONObject(raw);
            String answer;
            if (c.gemini) {
                JSONArray cand = r.optJSONArray("candidates");
                if (cand == null || cand.length() == 0) throw new Exception("no answer returned (possibly filtered)");
                JSONObject content = cand.getJSONObject(0).optJSONObject("content");
                if (content == null) throw new Exception("empty answer");
                JSONArray parts = content.optJSONArray("parts");
                StringBuilder sb = new StringBuilder();
                if (parts != null) for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
                answer = sb.toString().trim();
            } else {
                answer = r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            }
            if (answer.isEmpty()) throw new Exception("empty response");
            return answer;
        } finally { if (conn != null) conn.disconnect(); }
    }

    static String read(HttpURLConnection c, int code) throws Exception {
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        if (in == null) return "";
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > 4_000_000) break;
            }
            return out.toString("UTF-8");
        }
    }

    static String detail(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            Object e = o.opt("error");
            if (e instanceof JSONObject) return ((JSONObject) e).optString("message", "");
            if (e != null) return String.valueOf(e);
            return o.optString("message", raw.substring(0, Math.min(raw.length(), 160)));
        } catch (Exception ex) { return raw.substring(0, Math.min(raw.length(), 160)); }
    }
}
