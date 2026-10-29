package com.iqsoft.ibnseerin;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class SearchActivity extends Activity {
    private static final int DIRECT_LIMIT = 8;
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ENTRY = 1;
    private static final int TYPE_GENERAL = 2;

    private DbHelper db;
    private EditText searchBox;
    private TextView countView;
    private ListView listView;
    private final List<Item> items = new ArrayList<>();
    private boolean generalSearchMode = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_search);
        searchBox = findViewById(R.id.searchBox);
        countView = findViewById(R.id.countView);
        listView = findViewById(R.id.listView);
        db = new DbHelper(this);
        listView.setAdapter(new EntryAdapter());

        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) {
                generalSearchMode = false;
                loadSuggestions(s.toString());
            }
            public void afterTextChanged(Editable e) {}
        });

        String initialQuery = getIntent().getStringExtra("query");
        if (initialQuery != null && !initialQuery.trim().isEmpty()) {
            // setText() invokes the TextWatcher, so this immediately loads the requested results.
            searchBox.setText(initialQuery);
            searchBox.setSelection(searchBox.length());
        } else {
            loadAllEntries();
        }

        listView.setOnItemClickListener((parent, view, position, id) -> {
            Item it = items.get(position);
            if (it.type == TYPE_GENERAL) {
                generalSearchMode = true;
                loadGeneralSearch(searchBox.getText().toString());
            } else if (it.type == TYPE_ENTRY) {
                Intent i = new Intent(this, DetailActivity.class);
                i.putExtra("id", it.id);
                startActivity(i);
            }
        });
    }

    private void loadSuggestions(String q) {
        q = q == null ? "" : q.trim();
        if (q.isEmpty()) {
            loadAllEntries();
            return;
        }

        try {
            items.clear();
            Cursor c = db.searchIndexEntries(q, DIRECT_LIMIT);
            int directCount = 0;
            while (c.moveToNext()) {
                if (directCount == 0) items.add(Item.header("DIRECT DICTIONARY ENTRIES"));
                items.add(Item.entry(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)));
                directCount++;
            }
            c.close();

            int total = db.countSearch(q);
            if (total > 0) items.add(Item.generalSearch(total));

            if (directCount > 0) {
                countView.setText(directCount + " dictionary entries • " + total + " general matches");
            } else {
                countView.setText(total + " general matches");
            }
            notifyList();
        } catch (Exception e) {
            countView.setText("Unable to open dictionary: " + e.getMessage());
        }
    }

    private void loadGeneralSearch(String q) {
        q = q == null ? "" : q.trim();
        if (q.isEmpty()) {
            generalSearchMode = false;
            loadAllEntries();
            return;
        }
        try {
            items.clear();
            Cursor c = db.search(q);
            while (c.moveToNext()) {
                items.add(Item.entry(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)));
            }
            c.close();
            countView.setText(items.size() + " matches for \"" + q + "\"");
            notifyList();
        } catch (Exception e) {
            countView.setText("Unable to search dictionary: " + e.getMessage());
        }
    }

    private void loadAllEntries() {
        try {
            items.clear();
            Cursor c = db.search("");
            while (c.moveToNext()) {
                items.add(Item.entry(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)));
            }
            c.close();
            countView.setText(items.size() + " entries");
            notifyList();
        } catch (Exception e) {
            countView.setText("Unable to open dictionary: " + e.getMessage());
        }
    }

    private void notifyList() {
        ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
        listView.setSelection(0);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_home) {
            finish();
            return true;
        }
        if (id == R.id.menu_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }
        if (id == R.id.menu_contact) {
            startActivity(new Intent(this, ContactActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    static class Item {
        long id;
        String entry, detail;
        int page, type, count;
        private Item(long id, String entry, String detail, int page, int type, int count) {
            this.id = id; this.entry = entry; this.detail = detail; this.page = page;
            this.type = type; this.count = count;
        }
        static Item entry(long id, String e, String d, int p) { return new Item(id, e, d, p, TYPE_ENTRY, 0); }
        static Item header(String text) { return new Item(-1, text, "", 0, TYPE_HEADER, 0); }
        static Item generalSearch(int count) { return new Item(-1, "General search", "Show all " + count + " matching entries", 0, TYPE_GENERAL, count); }
    }

    class EntryAdapter extends BaseAdapter {
        public int getCount() { return items.size(); }
        public Object getItem(int p) { return items.get(p); }
        public long getItemId(int p) { return items.get(p).id; }
        public int getViewTypeCount() { return 3; }
        public int getItemViewType(int p) { return items.get(p).type; }

        public View getView(int p, View v, ViewGroup parent) {
            Item it = items.get(p);
            if (it.type == TYPE_HEADER) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.row_section_header, parent, false);
                ((TextView)v.findViewById(R.id.sectionTitle)).setText(it.entry);
            } else if (it.type == TYPE_GENERAL) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.row_general_search, parent, false);
                ((TextView)v.findViewById(R.id.generalTitle)).setText("General search");
                ((TextView)v.findViewById(R.id.generalSubtitle)).setText("Show all " + it.count + " matching entries");
            } else {
                if (v == null) v = getLayoutInflater().inflate(R.layout.row_entry, parent, false);
                ((TextView)v.findViewById(R.id.entryTitle)).setText(DetailActivity.formatEntryTitle(it.entry));
                ((TextView)v.findViewById(R.id.entryPreview)).setText(it.detail);
            }
            return v;
        }
    }
}
