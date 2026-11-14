package com.laiba.rebuilt;

import android.content.SharedPreferences;

/** Table of supported AI providers (all but Gemini speak the OpenAI chat-completions format). */
class Providers {
    static class P {
        final String id, name, base, model, keyUrl;
        final boolean gemini, vision;
        P(String id, String name, String base, String model, String keyUrl, boolean gemini, boolean vision) {
            this.id = id; this.name = name; this.base = base; this.model = model; this.keyUrl = keyUrl; this.gemini = gemini; this.vision = vision;
        }
    }

    static final P[] ALL = {
        new P("gemini", "Google Gemini (free)", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash-lite", "https://aistudio.google.com/apikey", true, true),
        new P("groq", "Groq (free, very fast)", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "https://console.groq.com/keys", false, false),
        new P("cerebras", "Cerebras (free, fast)", "https://api.cerebras.ai/v1", "llama3.1-8b", "https://cloud.cerebras.ai", false, false),
        new P("openrouter", "OpenRouter (free models)", "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free", "https://openrouter.ai/keys", false, false),
        new P("mistral", "Mistral (free tier)", "https://api.mistral.ai/v1", "mistral-small-latest", "https://console.mistral.ai/api-keys", false, false),
        new P("github", "GitHub Models (free)", "https://models.github.ai/inference", "openai/gpt-4o-mini", "https://github.com/settings/tokens", false, true),
        new P("nvidia", "NVIDIA NIM (free credits)", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct", "https://build.nvidia.com", false, false),
        new P("sambanova", "SambaNova (free)", "https://api.sambanova.ai/v1", "Meta-Llama-3.3-70B-Instruct", "https://cloud.sambanova.ai/apis", false, false),
        new P("huggingface", "Hugging Face (free credits)", "https://router.huggingface.co/v1", "meta-llama/Llama-3.3-70B-Instruct", "https://huggingface.co/settings/tokens", false, false),
        new P("deepseek", "DeepSeek (cheap)", "https://api.deepseek.com", "deepseek-chat", "https://platform.deepseek.com/api_keys", false, false),
        new P("openai", "OpenAI (paid)", "https://api.openai.com/v1", "gpt-4o-mini", "https://platform.openai.com/api-keys", false, true),
        new P("custom", "Custom OpenAI-compatible", "", "", "", false, false),
    };

    static P find(String id) { for (P p : ALL) if (p.id.equals(id)) return p; return null; }
    static int indexOf(String id) { for (int i = 0; i < ALL.length; i++) if (ALL[i].id.equals(id)) return i; return 0; }
    static String activeId(SharedPreferences p) { return p.getString("active", "gemini"); }
    static String key(SharedPreferences p, String id) { return p.getString("key_" + id, "").trim(); }
    static String model(SharedPreferences p, String id) { P q = find(id); return p.getString("model_" + id, q == null ? "" : q.model).trim(); }
    static String base(SharedPreferences p, String id) { P q = find(id); return p.getString("endpoint_" + id, q == null ? "" : q.base).trim(); }

    /** Moves the single key/model/endpoint of older versions into the per-provider slots. */
    static void migrate(SharedPreferences p) {
        if (p.contains("active") || !p.contains("key")) return;
        String[] ids = {"deepseek", "openai", "custom", "gemini"};
        int idx = 0;
        try { idx = Integer.parseInt(p.getString("provider", "0")); } catch (Exception ignored) { }
        String id = ids[Math.max(0, Math.min(3, idx))];
        p.edit().putString("key_" + id, p.getString("key", ""))
                .putString("model_" + id, p.getString("model", ""))
                .putString("endpoint_" + id, p.getString("endpoint", ""))
                .putString("active", id).apply();
    }
}
