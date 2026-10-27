package com.daedalus.animegf;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tiny persistent store for text snippets. Everything is kept as one JSON array in
 * SharedPreferences, so snippets survive app restarts (and are included in Android's
 * normal app backup because the app allows backup).
 */
final class SnippetStore {

    static final class Snippet {
        final String id;
        String title;     // optional label, may be empty
        String text;      // the snippet body
        long   updatedAt;

        Snippet(String id, String title, String text, long updatedAt) {
            this.id = id;
            this.title = title;
            this.text = text;
            this.updatedAt = updatedAt;
        }

        /** Title if present, otherwise the first non-empty line of the text. */
        String displayTitle() {
            if (title != null && !title.trim().isEmpty()) return title.trim();
            for (String line : text.split("\n")) {
                if (!line.trim().isEmpty()) return line.trim();
            }
            return "";
        }
    }

    private static final String PREFS = "animegf_snippets";
    private static final String KEY   = "items_v1";

    private final SharedPreferences prefs;
    private final List<Snippet> items = new ArrayList<>();

    SnippetStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    /** Snapshot, newest first. */
    List<Snippet> all() {
        return new ArrayList<>(items);
    }

    int count() {
        return items.size();
    }

    Snippet add(String title, String text) {
        Snippet s = new Snippet(UUID.randomUUID().toString(), clean(title), text, System.currentTimeMillis());
        items.add(0, s);
        save();
        return s;
    }

    void update(String id, String title, String text) {
        for (Snippet s : items) {
            if (s.id.equals(id)) {
                s.title = clean(title);
                s.text = text;
                s.updatedAt = System.currentTimeMillis();
                save();
                return;
            }
        }
    }

    void delete(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                items.remove(i);
                save();
                return;
            }
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private void load() {
        items.clear();
        String raw = prefs.getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String text = o.optString("text", "");
                if (text.isEmpty()) continue;
                items.add(new Snippet(
                    o.optString("id", UUID.randomUUID().toString()),
                    o.optString("title", ""),
                    text,
                    o.optLong("updatedAt", 0L)));
            }
        } catch (JSONException e) {
            // Corrupt data: start empty rather than crash.
            items.clear();
        }
    }

    private void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Snippet s : items) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("title", s.title == null ? "" : s.title);
                o.put("text", s.text);
                o.put("updatedAt", s.updatedAt);
                arr.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(KEY, arr.toString()).apply();
    }
}
