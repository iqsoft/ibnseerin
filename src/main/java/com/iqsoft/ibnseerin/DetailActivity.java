package com.iqsoft.ibnseerin;

import android.view.Menu;
import android.view.MenuItem;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.view.WindowInsets;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DetailActivity extends Activity {
    private static final Pattern PARENTHESIS = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern LEADING_REFERENCE = Pattern.compile(
            "^\\s*(?:See|Also\\s+see)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_detail);

        // Android 15+ may draw edge-to-edge when targeting SDK 35.
        // Add the navigation-bar inset to the ScrollView's bottom padding so
        // the final line of long dictionary entries is never hidden behind
        // the system navigation/gesture area.
        final android.widget.ScrollView detailScroll = findViewById(R.id.detailScroll);
        final int baseLeft = detailScroll.getPaddingLeft();
        final int baseTop = detailScroll.getPaddingTop();
        final int baseRight = detailScroll.getPaddingRight();
        final int baseBottom = detailScroll.getPaddingBottom();
        detailScroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(baseLeft, baseTop, baseRight, baseBottom + bottomInset);
            return insets;
        });
        detailScroll.requestApplyInsets();

        long id = getIntent().getLongExtra("id", -1);
        try {
            DbHelper helper = new DbHelper(this);
            Cursor c = helper.getEntry(id);
            if (c.moveToFirst()) {
                ((TextView) findViewById(R.id.detailTitle)).setText(formatEntryTitle(c.getString(1)));
                ((TextView) findViewById(R.id.detailPage)).setText("Book page " + c.getInt(3));

                TextView detail = findViewById(R.id.detailText);
                makeRelatedEntriesClickable(detail, c.getString(2), helper);
            }
            c.close();
        } catch (Exception e) {
            ((TextView) findViewById(R.id.detailText))
                    .setText("Unable to load entry: " + e.getMessage());
        }
    }

    /**
     * PDF text extraction occasionally loses an opening or closing parenthesis.
     * Keep all wording, but discard unmatched closing marks and close any groups
     * left open at the end so the displayed entry never has visibly broken brackets.
     */
    private String balanceParenthesesForDisplay(String source) {
        StringBuilder out = new StringBuilder(source.length() + 4);
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '(') {
                depth++;
                out.append(ch);
            } else if (ch == ')') {
                if (depth > 0) {
                    depth--;
                    out.append(ch);
                }
                // An unmatched closing parenthesis is a PDF/OCR artifact.
            } else {
                out.append(ch);
            }
        }
        while (depth-- > 0) out.append(')');
        return out.toString();
    }

    private void makeRelatedEntriesClickable(TextView view, String text, DbHelper helper) throws Exception {
        String source = balanceParenthesesForDisplay(text == null ? "" : text);
        SpannableString spannable = new SpannableString(source);
        // Parse parenthetical groups with a depth-aware scanner. PDF extraction
        // sometimes leaves a group unfinished or introduces nested parentheses;
        // neither case should disable links in otherwise valid reference lists.
        int i = 0;
        while (i < source.length()) {
            if (source.charAt(i) != '(') { i++; continue; }
            int contentStart = i + 1;
            int depth = 1;
            int j = contentStart;
            while (j < source.length() && depth > 0) {
                char ch = source.charAt(j);
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                j++;
            }
            boolean closed = depth == 0;
            int contentEnd = closed ? j - 1 : source.length();
            String group = source.substring(contentStart, contentEnd);
            if (LEADING_REFERENCE.matcher(group).matches() || group.indexOf(';') >= 0) {
                linkReferencesInGroup(spannable, group, contentStart, helper);
            }
            i = closed ? j : source.length();
        }

        view.setText(spannable);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setHighlightColor(Color.TRANSPARENT);
    }

    private void linkReferencesInGroup(SpannableString spannable,
                                       String inside, int contentStart, DbHelper helper) throws Exception {
        Matcher refMatcher = LEADING_REFERENCE.matcher(inside);
        String candidateText = inside;
        int candidateOffset = 0;
        if (refMatcher.matches()) {
            candidateText = refMatcher.group(1);
            candidateOffset = refMatcher.start(1);
        }

        int partStart = 0;
        for (int i = 0; i <= candidateText.length(); i++) {
            if (i != candidateText.length() && candidateText.charAt(i) != ';') continue;

            String rawPart = candidateText.substring(partStart, i);
            int leadingSpaces = countLeadingWhitespace(rawPart);
            int trailingSpaces = countTrailingWhitespace(rawPart);
            String target = rawPart.trim();

            if (!target.isEmpty()) {
                int start = contentStart + candidateOffset + partStart + leadingSpaces;
                int end = contentStart + candidateOffset + partStart + rawPart.length() - trailingSpaces;
                // Strip terminal punctuation only for lookup; visible text remains unchanged.
                linkTarget(spannable, start, end, target, helper);
            }
            partStart = i + 1;
        }
    }

    private void linkTarget(SpannableString text, int start, int end, final String target, final DbHelper helper) throws Exception {
        if (start < 0 || end <= start || end > text.length()) return;
        final List<DbHelper.EntryRef> targets = helper.findReferenceTargets(normalizeTarget(target));
        if (targets.isEmpty()) return;

        text.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) {
                if (targets.size() == 1) {
                    openEntry(targets.get(0).id);
                } else {
                    showVariantChooser(target, targets);
                }
            }

            @Override public void updateDrawState(TextPaint ds) {
                super.updateDrawState(ds);
                ds.setColor(Color.rgb(30, 90, 160));
                ds.setUnderlineText(true);
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void showVariantChooser(String target, List<DbHelper.EntryRef> variants) {
        String[] labels = new String[variants.size()];
        for (int i = 0; i < variants.size(); i++) {
            DbHelper.EntryRef v = variants.get(i);
            labels[i] = formatEntryTitle(v.entry) + " — " + variantDescription(v.detail);
        }

        new AlertDialog.Builder(this)
                .setTitle("Choose “" + target + "”")
                .setItems(labels, (dialog, which) -> openEntry(variants.get(which).id))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String variantDescription(String detail) {
        if (detail == null) return "";
        String s = detail.trim();
        if (s.startsWith("(") && s.indexOf(')') > 0) {
            s = s.substring(1, s.indexOf(')')).trim();
        }
        if (s.length() > 90) s = s.substring(0, 87).trim() + "…";
        return s;
    }

    private void openEntry(long id) {
        Intent intent = new Intent(DetailActivity.this, DetailActivity.class);
        intent.putExtra("id", id);
        startActivity(intent);
    }

    /** Display PDF-extracted subscript markers in the conventional form. */
    static String formatEntryTitle(String entry) {
        if (entry == null) return "";
        String s = entry.trim();
        if (s.matches(".*\\s+1$")) return s.substring(0, s.length() - 1).trim() + "₁";
        if (s.matches(".*\\s+2$")) return s.substring(0, s.length() - 1).trim() + "₂";
        if (s.endsWith("l") && hasVariantSiblingMarker(s.substring(0, s.length() - 1)))
            return s.substring(0, s.length() - 1) + "₁";
        if (s.matches(".*[12]$")) {
            char n = s.charAt(s.length() - 1);
            return s.substring(0, s.length() - 1) + (n == '1' ? "₁" : "₂");
        }
        return s;
    }

    // Used only for the visual title conversion of the known PDF "l" artifact.
    // The database resolver remains authoritative for whether it is truly a variant.
    private static boolean hasVariantSiblingMarker(String base) {
        // Known PDF extraction artifact: a trailing lowercase l is used for subscript 1.
        // If there is no sibling, retaining the original title is safer.
        return base.equalsIgnoreCase("Arm") || base.equalsIgnoreCase("Body") || base.equalsIgnoreCase("Box")
                || base.equalsIgnoreCase("Canopy") || base.equalsIgnoreCase("Chest") || base.equalsIgnoreCase("Dome")
                || base.equalsIgnoreCase("Driver") || base.equalsIgnoreCase("Earth") || base.equalsIgnoreCase("Grease")
                || base.equalsIgnoreCase("Gum") || base.equalsIgnoreCase("Hoof") || base.equalsIgnoreCase("Measure")
                || base.equalsIgnoreCase("Mercury") || base.equalsIgnoreCase("Pit") || base.equalsIgnoreCase("World");
    }

    private String normalizeTarget(String target) {
        String s = target == null ? "" : target.trim();
        while (!s.isEmpty()) {
            char ch = s.charAt(s.length() - 1);
            if (ch == '.' || ch == ',' || ch == ':' || ch == ';') s = s.substring(0, s.length() - 1).trim();
            else break;
        }
        return s;
    }

    private int countLeadingWhitespace(String s) {
        int i = 0; while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; return i;
    }
    private int countTrailingWhitespace(String s) {
        int i = s.length(); while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--; return s.length() - i;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.menu_home) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            return true;
        } else if (id == R.id.menu_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        } else if (id == R.id.menu_contact) {
            startActivity(new Intent(this, ContactActivity.class));
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
