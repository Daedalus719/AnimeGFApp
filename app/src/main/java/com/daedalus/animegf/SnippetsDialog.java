package com.daedalus.animegf;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Snippets" modal: a searchable list of saved text entries the user can copy, edit and
 * delete, plus a full editor screen for creating/changing one. Entries live in
 * {@link SnippetStore} and therefore survive app restarts.
 */
final class SnippetsDialog extends GlassDialog {

    private final SnippetStore store;

    // ── list screen ──
    private LinearLayout listScreen;
    private TextView     countText;
    private LinearLayout searchRow;
    private ScrollView   listScroll;
    private LinearLayout listContainer;
    private String       query = "";

    // ── editor screen ──
    private LinearLayout editScreen;
    private TextView     editTitle;
    private EditText     labelInput;
    private EditText     textInput;
    private TextView     charCount;
    private LinearLayout editFooter;
    private LinearLayout discardFooter;
    private LinearLayout saveButton;
    private boolean      editorOpen;
    private SnippetStore.Snippet editing;       // null = creating a new one
    private String       initialLabel = "";
    private String       initialText  = "";

    SnippetsDialog(Activity activity, SnippetStore store) {
        super(activity, 440, 640, true);
        this.store = store;
        buildListScreen();
        buildEditorScreen();
        panel.addView(listScreen, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        panel.addView(editScreen, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        editScreen.setVisibility(View.GONE);
        renderList();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  LIST SCREEN
    // ═════════════════════════════════════════════════════════════════════════

    private void buildListScreen() {
        listScreen = new LinearLayout(ctx);
        listScreen.setOrientation(LinearLayout.VERTICAL);

        countText = subtitle("");
        ImageView close = Ui.iconButton(ctx, R.drawable.ic_close, Ui.TEXT_DIM, 38, "Close");
        close.setOnClickListener(v -> dismissAnimated());
        listScreen.addView(buildHeader(R.drawable.ic_snippets, title("Snippets"), countText, close));

        // search
        searchRow = new LinearLayout(ctx);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 8), 0);
        searchRow.setBackground(Ui.rounded(Ui.CARD_FILL, Ui.dp(ctx, 14), Ui.dp(ctx, 1), Ui.CARD_STROKE));
        ImageView searchIcon = Ui.icon(ctx, R.drawable.ic_search, Ui.TEXT_FAINT, 18);
        LinearLayout.LayoutParams sip = new LinearLayout.LayoutParams(Ui.dp(ctx, 18), Ui.dp(ctx, 18));
        sip.rightMargin = Ui.dp(ctx, 8);
        searchRow.addView(searchIcon, sip);

        EditText search = new EditText(ctx);
        search.setBackground(null);
        search.setHint("Search snippets");
        search.setHintTextColor(Ui.TEXT_FAINT);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(14.5f);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setHighlightColor(0x554C9AFF);
        search.setPadding(0, Ui.dp(ctx, 11), 0, Ui.dp(ctx, 11));
        search.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.ROOT);
                renderList();
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard();
            return true;
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srp.setMargins(Ui.dp(ctx, 16), Ui.dp(ctx, 2), Ui.dp(ctx, 16), Ui.dp(ctx, 6));
        listScreen.addView(searchRow, srp);

        // list
        listScroll = new ScrollView(ctx);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listScroll.setFillViewport(true);
        listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 6), Ui.dp(ctx, 16), Ui.dp(ctx, 6));
        listScroll.addView(listContainer, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        listScreen.addView(listScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // footer
        LinearLayout footer = new LinearLayout(ctx);
        footer.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 16));
        LinearLayout add = Ui.gradientButton(ctx, R.drawable.ic_add, "New snippet");
        add.setOnClickListener(v -> openEditor(null));
        footer.addView(add, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 50)));
        listScreen.addView(footer);
    }

    private void renderList() {
        listContainer.removeAllViews();
        List<SnippetStore.Snippet> all = store.all();
        int total = all.size();

        if (total == 0) {
            countText.setText("Your saved text, always at hand");
        } else {
            countText.setText(total + (total == 1 ? " snippet" : " snippets") + " · tap one to copy");
        }
        searchRow.setVisibility(total == 0 ? View.GONE : View.VISIBLE);

        List<SnippetStore.Snippet> shown = new ArrayList<>();
        for (SnippetStore.Snippet s : all) {
            if (query.isEmpty()
                || s.text.toLowerCase(Locale.ROOT).contains(query)
                || (s.title != null && s.title.toLowerCase(Locale.ROOT).contains(query))) {
                shown.add(s);
            }
        }

        if (total == 0) {
            listContainer.addView(buildEmptyState(
                "No snippets yet",
                "Save text you use often — replies, prompts, character notes — and copy it with one tap."));
        } else if (shown.isEmpty()) {
            listContainer.addView(buildEmptyState("No matches", "Nothing matches “" + query + "”."));
        } else {
            for (SnippetStore.Snippet s : shown) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = Ui.dp(ctx, 10);
                listContainer.addView(buildCard(s), lp);
            }
        }
    }

    private View buildEmptyState(String heading, String body) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 40), Ui.dp(ctx, 24), Ui.dp(ctx, 24));

        LinearLayout badge = new LinearLayout(ctx);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.circle(0x1F4C9AFF, Ui.dp(ctx, 1), 0x554C9AFF));
        badge.addView(Ui.icon(ctx, R.drawable.ic_snippets, Ui.BLUE, 28));
        col.addView(badge, new LinearLayout.LayoutParams(Ui.dp(ctx, 68), Ui.dp(ctx, 68)));

        TextView h = Ui.text(ctx, heading, 16, Ui.TEXT, true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Ui.dp(ctx, 16);
        col.addView(h, hp);

        TextView b = Ui.text(ctx, body, 13.5f, Ui.TEXT_DIM, false);
        b.setGravity(Gravity.CENTER);
        b.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(ctx, 6);
        col.addView(b, bp);
        return col;
    }

    private View buildCard(final SnippetStore.Snippet s) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(Ui.CARD_FILL, Ui.dp(ctx, 16), Ui.dp(ctx, 1), Ui.CARD_STROKE));
        card.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 12));

        // tappable text area → copies
        LinearLayout textArea = new LinearLayout(ctx);
        textArea.setOrientation(LinearLayout.VERTICAL);
        textArea.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4));
        textArea.setBackground(Ui.ripple(new ColorDrawable(Color.TRANSPARENT), Ui.dp(ctx, 10), 0x26FFFFFF));
        textArea.setClickable(true);

        String title = s.title == null ? "" : s.title.trim();
        boolean hasTitle = !title.isEmpty();
        if (hasTitle) {
            TextView t = Ui.text(ctx, title, 15, Ui.TEXT, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            textArea.addView(t);
        }
        TextView preview = Ui.text(ctx, s.text, 13.5f, hasTitle ? Ui.TEXT_DIM : Ui.TEXT, false);
        preview.setMaxLines(hasTitle ? 3 : 4);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (hasTitle) pp.topMargin = Ui.dp(ctx, 4);
        textArea.addView(preview, pp);
        card.addView(textArea, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // hairline
        View line = new View(ctx);
        line.setBackgroundColor(Ui.HAIRLINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(ctx, 1)));
        lp.topMargin = Ui.dp(ctx, 10);
        lp.bottomMargin = Ui.dp(ctx, 10);
        card.addView(line, lp);

        // normal actions
        final LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        final LinearLayout copyBtn = Ui.pill(ctx, R.drawable.ic_copy, "Copy", Ui.GREEN, 0x1F34D399, 0x4034D399);
        LinearLayout editBtn = Ui.pill(ctx, R.drawable.ic_edit, "Edit", Ui.BLUE, 0x1F4C9AFF, 0x404C9AFF);
        LinearLayout delBtn  = Ui.pill(ctx, R.drawable.ic_delete, "Delete", Ui.DANGER, 0x1AFF6B7A, 0x40FF6B7A);
        actions.addView(copyBtn);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        editLp.leftMargin = Ui.dp(ctx, 8);
        actions.addView(editBtn, editLp);
        actions.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        actions.addView(delBtn);
        card.addView(actions);

        // delete confirmation (swaps in for the actions row)
        final LinearLayout confirm = new LinearLayout(ctx);
        confirm.setOrientation(LinearLayout.HORIZONTAL);
        confirm.setGravity(Gravity.CENTER_VERTICAL);
        confirm.setVisibility(View.GONE);
        TextView ask = Ui.text(ctx, "Delete this snippet?", 13.5f, Ui.TEXT, true);
        confirm.addView(ask, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout cancelBtn = Ui.pill(ctx, R.drawable.ic_close, "Cancel", Ui.TEXT_DIM, Ui.CARD_FILL, Ui.CARD_STROKE);
        LinearLayout yesBtn = Ui.pill(ctx, R.drawable.ic_delete, "Delete", Ui.NAVY_DEEP, Ui.DANGER, Ui.DANGER);
        confirm.addView(cancelBtn);
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        yesLp.leftMargin = Ui.dp(ctx, 8);
        confirm.addView(yesBtn, yesLp);
        card.addView(confirm);

        textArea.setOnClickListener(v -> copy(s, copyBtn));
        copyBtn.setOnClickListener(v -> copy(s, copyBtn));
        editBtn.setOnClickListener(v -> openEditor(s));
        delBtn.setOnClickListener(v -> {
            actions.setVisibility(View.GONE);
            confirm.setVisibility(View.VISIBLE);
        });
        cancelBtn.setOnClickListener(v -> {
            confirm.setVisibility(View.GONE);
            actions.setVisibility(View.VISIBLE);
        });
        yesBtn.setOnClickListener(v -> {
            store.delete(s.id);
            renderList();
        });
        return card;
    }

    private void copy(SnippetStore.Snippet s, final LinearLayout copyBtn) {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(s.displayTitle(), s.text));
        }
        copyBtn.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        Ui.setPill(ctx, copyBtn, R.drawable.ic_check, "Copied", Ui.GREEN);
        copyBtn.postDelayed(
            () -> Ui.setPill(ctx, copyBtn, R.drawable.ic_copy, "Copy", Ui.GREEN), 1400);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  EDITOR SCREEN
    // ═════════════════════════════════════════════════════════════════════════

    private void buildEditorScreen() {
        editScreen = new LinearLayout(ctx);
        editScreen.setOrientation(LinearLayout.VERTICAL);

        // header: back arrow + title
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 18), Ui.dp(ctx, 20), Ui.dp(ctx, 12));
        ImageView back = Ui.iconButton(ctx, R.drawable.ic_back, Ui.TEXT, 38, "Back");
        back.setOnClickListener(v -> requestCloseEditor());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(Ui.dp(ctx, 38), Ui.dp(ctx, 38));
        backLp.rightMargin = Ui.dp(ctx, 10);
        header.addView(back, backLp);
        editTitle = title("New snippet");
        header.addView(editTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        editScreen.addView(header);

        // body
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 4), Ui.dp(ctx, 20), 0);

        body.addView(fieldLabel("TITLE  ·  OPTIONAL"));
        labelInput = new EditText(ctx);
        styleInput(labelInput, "e.g. Greeting");
        labelInput.setSingleLine(true);
        labelInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        labelInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        body.addView(labelInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView l2 = fieldLabel("SNIPPET");
        ((LinearLayout.LayoutParams) l2.getLayoutParams()).topMargin = Ui.dp(ctx, 16);
        body.addView(l2);
        textInput = new EditText(ctx);
        styleInput(textInput, "Type or paste the text you want to keep…");
        textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        textInput.setGravity(Gravity.TOP | Gravity.START);
        textInput.setMinHeight(Ui.dp(ctx, 90));
        textInput.setVerticalScrollBarEnabled(true);
        textInput.setLineSpacing(0, 1.15f);
        body.addView(textInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        charCount = Ui.text(ctx, "0 characters", 11.5f, Ui.TEXT_FAINT, false);
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ccp.gravity = Gravity.END;
        ccp.topMargin = Ui.dp(ctx, 6);
        body.addView(charCount, ccp);

        editScreen.addView(body, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextWatcher watcher = new SimpleWatcher() {
            @Override public void afterTextChanged(Editable e) { refreshEditorState(); }
        };
        labelInput.addTextChangedListener(watcher);
        textInput.addTextChangedListener(watcher);

        // footer: [Cancel][Save]  /  discard confirmation
        LinearLayout footerHost = new LinearLayout(ctx);
        footerHost.setOrientation(LinearLayout.VERTICAL);
        footerHost.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 16));

        editFooter = new LinearLayout(ctx);
        editFooter.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout cancel = Ui.ghostButton(ctx, "Cancel");
        cancel.setOnClickListener(v -> requestCloseEditor());
        saveButton = Ui.gradientButton(ctx, R.drawable.ic_check, "Save");
        saveButton.setOnClickListener(v -> saveEditor());
        editFooter.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(ctx, 50), 1f));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, Ui.dp(ctx, 50), 1.4f);
        saveLp.leftMargin = Ui.dp(ctx, 10);
        editFooter.addView(saveButton, saveLp);
        footerHost.addView(editFooter);

        discardFooter = new LinearLayout(ctx);
        discardFooter.setOrientation(LinearLayout.VERTICAL);
        discardFooter.setVisibility(View.GONE);
        TextView ask = Ui.text(ctx, "Discard your changes?", 14, Ui.TEXT, true);
        ask.setGravity(Gravity.CENTER_HORIZONTAL);
        discardFooter.addView(ask, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout keep = Ui.ghostButton(ctx, "Keep editing");
        keep.setOnClickListener(v -> {
            discardFooter.setVisibility(View.GONE);
            editFooter.setVisibility(View.VISIBLE);
        });
        LinearLayout discard = Ui.ghostButton(ctx, "Discard");
        ((TextView) discard.getChildAt(0)).setTextColor(Ui.DANGER);
        discard.setOnClickListener(v -> closeEditor());
        btnRow.addView(keep, new LinearLayout.LayoutParams(0, Ui.dp(ctx, 50), 1f));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, Ui.dp(ctx, 50), 1f);
        dlp.leftMargin = Ui.dp(ctx, 10);
        btnRow.addView(discard, dlp);
        LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brp.topMargin = Ui.dp(ctx, 10);
        discardFooter.addView(btnRow, brp);
        footerHost.addView(discardFooter);

        editScreen.addView(footerHost);
    }

    private TextView fieldLabel(String s) {
        TextView t = Ui.text(ctx, s, 11, Ui.TEXT_FAINT, true);
        t.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(ctx, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private void styleInput(final EditText e, String hint) {
        final Drawable normal  = Ui.rounded(0x1AFFFFFF, Ui.dp(ctx, 14), Ui.dp(ctx, 1), 0x2EFFFFFF);
        final Drawable focused = Ui.rounded(0x1F4C9AFF, Ui.dp(ctx, 14),
            Ui.dp(ctx, 1) + 1, Ui.BLUE);
        e.setBackground(normal);
        e.setHint(hint);
        e.setHintTextColor(Ui.TEXT_FAINT);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(15);
        e.setHighlightColor(0x554C9AFF);
        e.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12));
        e.setOnFocusChangeListener((v, has) -> e.setBackground(has ? focused : normal));
    }

    private void openEditor(SnippetStore.Snippet s) {
        editing = s;
        initialLabel = s == null || s.title == null ? "" : s.title;
        initialText  = s == null ? "" : s.text;
        editTitle.setText(s == null ? "New snippet" : "Edit snippet");
        labelInput.setText(initialLabel);
        textInput.setText(initialText);
        discardFooter.setVisibility(View.GONE);
        editFooter.setVisibility(View.VISIBLE);
        refreshEditorState();

        editorOpen = true;
        listScreen.setVisibility(View.GONE);
        editScreen.setAlpha(0f);
        editScreen.setTranslationX(Ui.dp(ctx, 24));
        editScreen.setVisibility(View.VISIBLE);
        editScreen.animate().alpha(1f).translationX(0f).setDuration(200).start();

        textInput.postDelayed(() -> {
            textInput.requestFocus();
            textInput.setSelection(textInput.getText().length());
            InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    private void closeEditor() {
        hideKeyboard();
        editorOpen = false;
        editing = null;
        textInput.clearFocus();
        labelInput.clearFocus();
        editScreen.animate().cancel();
        editScreen.setVisibility(View.GONE);
        listScreen.setAlpha(0f);
        listScreen.setTranslationX(-Ui.dp(ctx, 24));
        listScreen.setVisibility(View.VISIBLE);
        listScreen.animate().alpha(1f).translationX(0f).setDuration(200).start();
        renderList();
    }

    private boolean isDirty() {
        return !labelInput.getText().toString().equals(initialLabel)
            || !textInput.getText().toString().equals(initialText);
    }

    /** Back / cancel / outside tap while editing: ask before throwing work away. */
    private void requestCloseEditor() {
        if (isDirty() && !textInput.getText().toString().trim().isEmpty()) {
            hideKeyboard();
            editFooter.setVisibility(View.GONE);
            discardFooter.setVisibility(View.VISIBLE);
        } else {
            closeEditor();
        }
    }

    private void refreshEditorState() {
        int len = textInput.getText().length();
        charCount.setText(len + (len == 1 ? " character" : " characters"));
        boolean ok = !textInput.getText().toString().trim().isEmpty();
        saveButton.setEnabled(ok);
        saveButton.setAlpha(ok ? 1f : 0.4f);
    }

    private void saveEditor() {
        String text = textInput.getText().toString().replaceAll("\\s+$", "");
        if (text.trim().isEmpty()) {
            textInput.requestFocus();
            return;
        }
        String label = labelInput.getText().toString();
        if (editing == null) {
            store.add(label, text);
        } else {
            store.update(editing.id, label, text);
        }
        closeEditor();
        listScroll.post(() -> listScroll.scrollTo(0, 0));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DISMISS BEHAVIOUR
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void onBackRequested() {
        if (editorOpen) requestCloseEditor();
        else dismissAnimated();
    }

    @Override
    protected void onOutsideTap() {
        if (editorOpen && isDirty() && !textInput.getText().toString().trim().isEmpty()) {
            requestCloseEditor();   // don't lose unsaved text to a stray tap
        } else {
            dismissAnimated();
        }
    }

    /** TextWatcher with no-op before/on callbacks. */
    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
    }
}
