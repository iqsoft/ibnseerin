package com.iqsoft.ibnseerin;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DbHelper {
    private static final String DB_NAME = "ibn_seerin.db";
    private final Context context;
    private SQLiteDatabase db;
    private Map<String, List<EntryRef>> variantCache;

    public DbHelper(Context context) { this.context = context.getApplicationContext(); }

    public SQLiteDatabase open() throws Exception {
        if (db != null && db.isOpen()) return db;
        File dbFile = context.getDatabasePath(DB_NAME);
        if (!dbFile.exists()) {
            File parent = dbFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = context.getAssets().open(DB_NAME); FileOutputStream out = new FileOutputStream(dbFile)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        db = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        return db;
    }

    public Cursor searchIndexEntries(String q, int limit) throws Exception {
        SQLiteDatabase d = open();
        if (q == null) q = "";
        q = q.trim();
        String lower = q.toLowerCase(java.util.Locale.ROOT);
        String contains = "%" + lower + "%";
        String prefix = lower + "%";
        return d.rawQuery(
                "SELECT id, entry, detail, pdf_page FROM entries " +
                "WHERE lower(entry) LIKE ? " +
                "ORDER BY CASE WHEN lower(trim(entry)) = ? THEN 0 " +
                "WHEN lower(entry) LIKE ? THEN 1 ELSE 2 END, " +
                "length(entry), entry COLLATE NOCASE LIMIT ?",
                new String[]{contains, lower, prefix, String.valueOf(limit)});
    }

    public int countSearch(String q) throws Exception {
        SQLiteDatabase d = open();
        if (q == null) q = "";
        q = q.trim();
        if (q.isEmpty()) return 0;
        String like = "%" + q.toLowerCase(java.util.Locale.ROOT) + "%";
        Cursor c = d.rawQuery(
                "SELECT COUNT(*) FROM entries WHERE lower(entry) LIKE ? OR lower(detail) LIKE ?",
                new String[]{like, like});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public Cursor search(String q) throws Exception {
        SQLiteDatabase d = open();
        if (q == null) q = "";
        q = q.trim();
        if (q.isEmpty()) {
            return d.rawQuery("SELECT id, entry, detail, pdf_page FROM entries ORDER BY entry COLLATE NOCASE", null);
        }
        String like = "%" + q.toLowerCase(Locale.ROOT) + "%";
        return d.rawQuery("SELECT id, entry, detail, pdf_page FROM entries WHERE lower(entry) LIKE ? OR lower(detail) LIKE ? ORDER BY entry COLLATE NOCASE", new String[]{like, like});
    }

    public Cursor getEntry(long id) throws Exception {
        return open().rawQuery("SELECT id, entry, detail, pdf_page FROM entries WHERE id=?", new String[]{String.valueOf(id)});
    }

    /** Exact entry lookup, including a typed subscript form such as Mercury₁. */
    public long findEntryId(String entryName) throws Exception {
        if (entryName == null) return -1;
        String target = normalizeLookup(entryName);
        if (target.isEmpty()) return -1;

        Cursor c = open().rawQuery(
                "SELECT id FROM entries WHERE lower(trim(entry)) = lower(?) LIMIT 1",
                new String[]{target});
        try {
            if (c.moveToFirst()) return c.getLong(0);
        } finally {
            c.close();
        }

        // Allow a clean subscript form (e.g. Mercury₁) to resolve to the
        // source PDF extraction form (e.g. Mercuryl).
        VariantQuery vq = parseVariantMarker(target);
        if (vq.number > 0) {
            for (EntryRef ref : getVariants(vq.base)) {
                if (ref.variantNumber == vq.number) return ref.id;
            }
        }
        return -1;
    }

    /**
     * Returns all numbered/subscripted variants for a base entry.
     * A variant is only recognized when there is more than one entry sharing
     * the same base, preventing ordinary words ending in "l" or "2" from
     * being misclassified.
     */
    public List<EntryRef> getVariants(String base) throws Exception {
        ensureVariantCache();
        String key = normalizeBase(base);
        List<EntryRef> list = variantCache.get(key);
        if (list == null) return Collections.emptyList();
        return new ArrayList<>(list);
    }

    /** Find a base entry's variants, or an exact entry if it is unambiguous. */
    public List<EntryRef> findReferenceTargets(String target) throws Exception {
        String normalized = normalizeLookup(target);
        if (normalized.isEmpty()) return Collections.emptyList();

        // Exact entry wins. This preserves normal links such as "Orphan".
        Cursor c = open().rawQuery(
                "SELECT id, entry, detail, pdf_page FROM entries WHERE lower(trim(entry)) = lower(?) LIMIT 1",
                new String[]{normalized});
        try {
            if (c.moveToFirst()) {
                ArrayList<EntryRef> one = new ArrayList<>();
                one.add(new EntryRef(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), 0));
                return one;
            }
        } finally {
            c.close();
        }

        VariantQuery typed = parseVariantMarker(normalized);
        if (typed.number > 0) {
            for (EntryRef ref : getVariants(typed.base)) {
                if (ref.variantNumber == typed.number) {
                    ArrayList<EntryRef> one = new ArrayList<>();
                    one.add(ref);
                    return one;
                }
            }
            return Collections.emptyList();
        }

        return getVariants(normalized);
    }

    private synchronized void ensureVariantCache() throws Exception {
        if (variantCache != null) return;
        variantCache = new HashMap<>();
        Cursor c = open().rawQuery("SELECT id, entry, detail, pdf_page FROM entries", null);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String entry = c.getString(1);
                String detail = c.getString(2);
                int page = c.getInt(3);
                VariantQuery parsed = parseVariantMarker(entry);
                if (parsed.number > 0) {
                    String key = normalizeBase(parsed.base);
                    List<EntryRef> group = variantCache.get(key);
                    if (group == null) {
                        group = new ArrayList<>();
                        variantCache.put(key, group);
                    }
                    group.add(new EntryRef(id, entry, detail, page, parsed.number));
                }
            }
        } finally {
            c.close();
        }

        // Keep only genuine variant groups (two or more variants).
        List<String> remove = new ArrayList<>();
        for (Map.Entry<String, List<EntryRef>> e : variantCache.entrySet()) {
            if (e.getValue().size() < 2) remove.add(e.getKey());
            else e.getValue().sort(Comparator.comparingInt(a -> a.variantNumber));
        }
        for (String key : remove) variantCache.remove(key);
    }

    /**
     * The PDF extraction represents subscript 1 inconsistently as "1" or
     * lowercase "l" (and sometimes inserts a space). We recognize both, but
     * only as a variant when a matching sibling exists.
     */
    private VariantQuery parseVariantMarker(String value) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) return new VariantQuery(s, 0);

        // Unicode subscripts commonly used by the app/UI.
        if (s.endsWith("₁")) return new VariantQuery(s.substring(0, s.length() - 1).trim(), 1);
        if (s.endsWith("₂")) return new VariantQuery(s.substring(0, s.length() - 1).trim(), 2);

        if (s.matches(".*\\s+[12]$")) {
            char n = s.charAt(s.length() - 1);
            return new VariantQuery(s.substring(0, s.length() - 1).trim(), n - '0');
        }
        if (s.matches(".*[12]$")) {
            char n = s.charAt(s.length() - 1);
            return new VariantQuery(s.substring(0, s.length() - 1).trim(), n - '0');
        }
        if (s.endsWith("l") || s.endsWith("L")) {
            return new VariantQuery(s.substring(0, s.length() - 1).trim(), 1);
        }
        return new VariantQuery(s, 0);
    }

    private String normalizeBase(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeLookup(String s) {
        if (s == null) return "";
        String v = s.trim();
        while (!v.isEmpty()) {
            char ch = v.charAt(v.length() - 1);
            if (ch == '.' || ch == ',' || ch == ':' || ch == ';') v = v.substring(0, v.length() - 1).trim();
            else break;
        }
        return v;
    }

    private static class VariantQuery {
        final String base; final int number;
        VariantQuery(String base, int number) { this.base = base; this.number = number; }
    }

    public static class EntryRef {
        public final long id;
        public final String entry;
        public final String detail;
        public final int page;
        public final int variantNumber;

        EntryRef(long id, String entry, String detail, int page, int variantNumber) {
            this.id = id; this.entry = entry; this.detail = detail; this.page = page; this.variantNumber = variantNumber;
        }
    }
}
