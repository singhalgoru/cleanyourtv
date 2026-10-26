package io.github.singhalgoru.watchroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class WatchroomActivity extends Activity {
    private static final String TAG = "Watchroom";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Catalog.Client client = new Catalog.Client();
    private final Random random = new Random();
    private SharedPreferences prefs;
    private LinearLayout content;
    private Button[] tabs;
    private List<Catalog.Title> titles = Collections.emptyList();
    private List<Subscription> subscriptions;
    private Catalog.Kind kind = Catalog.Kind.TV;
    private String tvQuery = "Planet";
    private String movieQuery = "Bollywood";
    private String failure;
    private Future<?> pending;
    private int generation;
    private int tab;
    private int pickIndex;
    private boolean resumed;
    private boolean loading;
    private boolean needsLoad = true;
    private boolean pickOnLoad;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("watchroom", MODE_PRIVATE);
        if (state == null) {
            int requested = getIntent().getIntExtra("tab", 0);
            tab = requested == 1 || requested == 3 || requested == 4 ? requested : 0;
            kind = tab == 1 ? Catalog.Kind.MOVIES : Catalog.Kind.TV;
            pickOnLoad = getIntent().getBooleanExtra("pick", false);
        } else {
            tvQuery = state.getString("tvQuery", tvQuery);
            movieQuery = state.getString("movieQuery", movieQuery);
            kind = state.getBoolean("movies") ? Catalog.Kind.MOVIES : Catalog.Kind.TV;
            tab = state.getInt("tab");
            if (tab == 2) tab = kind == Catalog.Kind.TV ? 0 : 1;
            pickOnLoad = state.getBoolean("pickOnLoad");
        }
        LinearLayout root = column();
        root.setPadding(dp(38), dp(24), dp(38), dp(20));
        root.setBackgroundColor(Color.rgb(12, 22, 37));
        root.addView(text(getString(R.string.app_name), 30, Color.rgb(232, 187, 120)));
        root.addView(text(getString(R.string.tagline), 15, Color.LTGRAY));
        LinearLayout nav = row();
        nav.setPadding(0, dp(16), 0, dp(12));
        int[] labels = {R.string.tv_tab, R.string.movies_tab, R.string.pick_tab,
                R.string.subscriptions_tab, R.string.about_tab};
        tabs = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int target = i;
            tabs[i] = button(getString(labels[i]), () -> select(target));
            nav.addView(tabs[i], weighted(dp(48)));
        }
        root.addView(nav);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (tab == 3) {
            needsLoad = false;
            renderSubscriptions();
        } else if (tab == 4) {
            needsLoad = false;
            renderAbout();
        } else {
            renderCatalog();
        }
        tabs[tab].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (needsLoad && tab < 2) load();
        else if (tab == 3) renderSubscriptions();
    }

    @Override
    protected void onPause() {
        resumed = false;
        cancelLoad();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelLoad();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("tvQuery", tvQuery);
        state.putString("movieQuery", movieQuery);
        state.putBoolean("movies", kind == Catalog.Kind.MOVIES);
        state.putInt("tab", tab);
        state.putBoolean("pickOnLoad", pickOnLoad);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        if (tab == 2) {
            tab = kind == Catalog.Kind.TV ? 0 : 1;
            renderCatalog();
            tabs[tab].requestFocus();
        } else {
            super.onBackPressed();
        }
    }

    private void select(int target) {
        if (target == 2) {
            if (loading || titles.isEmpty()) {
                error(getString(R.string.pick_first));
                return;
            }
            tab = 2;
            pickIndex = random.nextInt(titles.size());
            renderPick();
            return;
        }
        cancelLoad();
        tab = target;
        needsLoad = false;
        if (target < 2) {
            kind = target == 0 ? Catalog.Kind.TV : Catalog.Kind.MOVIES;
            titles = Collections.emptyList();
            failure = null;
            load();
        } else if (target == 3) {
            renderSubscriptions();
        } else {
            renderAbout();
        }
    }

    private void cancelLoad() {
        if (loading) needsLoad = true;
        loading = false;
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null;
        client.cancel();
        main.removeCallbacksAndMessages(null);
    }

    private String query() {
        return kind == Catalog.Kind.TV ? tvQuery : movieQuery;
    }

    private String provider() {
        return kind == Catalog.Kind.TV ? "TVmaze" : "Apple";
    }

    private int notice() {
        return kind == Catalog.Kind.TV ? R.string.tv_notice : R.string.movie_notice;
    }

    private void load() {
        if (!resumed) {
            needsLoad = true;
            return;
        }
        cancelLoad();
        loading = true;
        needsLoad = false;
        failure = null;
        titles = Collections.emptyList();
        renderCatalog();
        int ticket = ++generation;
        Catalog.Kind requestedKind = kind;
        String requestedQuery = query();
        pending = io.submit(() -> {
            try {
                List<Catalog.Title> found = client.search(requestedKind, requestedQuery);
                deliver(ticket, found, null);
            } catch (IOException | JSONException | IllegalArgumentException e) {
                if (Thread.currentThread().isInterrupted()) return;
                Log.w(TAG, "Catalog request failed", e);
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                deliver(ticket, Collections.emptyList(), message);
            }
        });
    }

    private void deliver(int ticket, List<Catalog.Title> found, String message) {
        main.post(() -> {
            if (!resumed || isDestroyed() || ticket != generation) return;
            loading = false;
            pending = null;
            titles = found;
            failure = message;
            if (pickOnLoad && message == null && !found.isEmpty()) {
                pickOnLoad = false;
                tab = 2;
                pickIndex = random.nextInt(found.size());
                renderPick();
            } else {
                renderCatalog();
            }
        });
    }

    private void renderCatalog() {
        content.removeAllViews();
        content.addView(paragraph(getString(notice())));
        LinearLayout actions = row();
        actions.addView(button(getString(R.string.search_label, query()), this::searchDialog),
                weighted(dp(48)));
        actions.addView(button(getString(R.string.refresh), this::load), weighted(dp(48)));
        content.addView(actions);
        if (loading) {
            content.addView(paragraph(getString(R.string.loading, provider())));
        } else if (failure != null) {
            content.addView(paragraph(getString(R.string.load_error, provider(), failure)));
        } else if (titles.isEmpty()) {
            content.addView(paragraph(getString(R.string.empty_results,
                    getString(kind == Catalog.Kind.TV ? R.string.tv_tab : R.string.movies_tab), query())));
        } else {
            content.addView(paragraph(getString(R.string.result_count, titles.size(), query())));
            for (int i = 0; i < titles.size(); i += 2) {
                LinearLayout line = row();
                for (int j = i; j < Math.min(i + 2, titles.size()); j++) {
                    Catalog.Title title = titles.get(j);
                    Button card = button(title.name + "\n" + metadata(title), () -> details(title));
                    card.setTextSize(17);
                    card.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    card.setPadding(dp(20), dp(10), dp(20), dp(10));
                    line.addView(card, weighted(dp(132)));
                }
                if (line.getChildCount() == 1) {
                    line.addView(new View(this), weighted(dp(132)));
                }
                content.addView(line);
            }
        }
    }

    private void searchDialog() {
        EditText input = input(R.string.search_hint);
        input.setText(query());
        input.setSelectAllOnFocus(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout form = form();
        form.addView(input);
        TextView problem = paragraph("");
        form.addView(problem);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.search_title,
                        getString(kind == Catalog.Kind.TV ? R.string.tv_tab : R.string.movies_tab)))
                .setView(form).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.search, null).create();
        Runnable search = () -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty() || value.length() > 80) {
                problem.setText(R.string.search_hint);
                input.requestFocus();
                return;
            }
            if (kind == Catalog.Kind.TV) tvQuery = value;
            else movieQuery = value;
            dialog.dismiss();
            load();
        };
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> search.run()));
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search.run();
                return true;
            }
            return false;
        });
        dialog.show();
    }

    private String supplied(String value) {
        return value.isEmpty() ? getString(R.string.not_supplied) : value;
    }

    private String metadata(Catalog.Title title) {
        String score = getString(R.string.imdb_unavailable);
        String outlet = title.kind == Catalog.Kind.TV
                ? getString(R.string.original_outlet, supplied(title.outlet))
                : getString(R.string.apple_outlet);
        return getString(R.string.catalog_meta, supplied(title.year), supplied(title.genre), score, outlet);
    }

    private void details(Catalog.Title title) {
        String extra = getString(title.kind == Catalog.Kind.TV ? R.string.tv_notice : R.string.movie_notice);
        if (!title.age.isEmpty()) extra += "\n\n" + getString(R.string.age_rating, title.age);
        new AlertDialog.Builder(this).setTitle(title.name)
                .setMessage(metadata(title) + "\n\n" + extra)
                .setPositiveButton(R.string.source_listing, (d, w) -> openLink(title.link))
                .setNegativeButton(R.string.close, null).show();
    }

    private void renderPick() {
        content.removeAllViews();
        content.addView(paragraph(getString(notice())));
        content.addView(paragraph(getString(R.string.pick_hint)));
        Catalog.Title title = titles.get(pickIndex);
        Button card = button(title.name + "\n\n" + metadata(title), () -> details(title));
        card.setTextSize(25);
        card.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                movePick(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            }
            return false;
        });
        content.addView(card, new LinearLayout.LayoutParams(-1, dp(230)));
        content.addView(paragraph(getString(R.string.pick_position, pickIndex + 1, titles.size())));
        LinearLayout actions = row();
        actions.addView(button(getString(R.string.previous), () -> movePick(-1)), weighted(dp(48)));
        actions.addView(button(getString(R.string.another), () -> {
            if (titles.size() > 1) {
                pickIndex = (pickIndex + 1 + random.nextInt(titles.size() - 1)) % titles.size();
            }
            renderPick();
        }), weighted(dp(48)));
        actions.addView(button(getString(R.string.next), () -> movePick(1)), weighted(dp(48)));
        content.addView(actions);
        card.requestFocus();
    }

    private void movePick(int direction) {
        pickIndex = (pickIndex + direction + titles.size()) % titles.size();
        renderPick();
    }

    private void renderSubscriptions() {
        content.removeAllViews();
        content.addView(paragraph(getString(R.string.subscriptions_notice)));
        try {
            subscriptions = Subscription.decode(prefs.getString("subscriptions", "[]"));
        } catch (JSONException | IllegalArgumentException e) {
            subscriptions = null;
            Log.e(TAG, "Could not read saved subscriptions", e);
            content.addView(paragraph(getString(R.string.subscription_read_error)));
            content.addView(button(getString(R.string.reset), () ->
                    new AlertDialog.Builder(this).setMessage(R.string.reset_confirmation)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.reset, (d, w) ->
                                    saveSubscriptions(new ArrayList<>())).show()));
            return;
        }
        content.addView(button(getString(R.string.add_subscription), () -> editSubscription(-1)));
        if (subscriptions.isEmpty()) content.addView(paragraph(getString(R.string.empty_subscriptions)));
        for (int i = 0; i < subscriptions.size(); i++) {
            int index = i;
            Subscription subscription = subscriptions.get(i);
            Button card = button(subscription.name + "\n" + renewal(subscription),
                    () -> subscriptionDetails(index));
            card.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            card.setPadding(dp(20), dp(10), dp(20), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(100));
            lp.bottomMargin = dp(10);
            content.addView(card, lp);
        }
    }

    private String renewal(Subscription subscription) {
        LocalDate today = LocalDate.now();
        long days = subscription.daysUntil(today);
        String countdown = days == 0 ? getString(R.string.due_today)
                : days > 0 ? getString(R.string.due_in, days) : getString(R.string.overdue, -days);
        int[] repeats = {R.string.once, R.string.monthly, R.string.yearly};
        return getString(R.string.renewal_line, subscription.nextDate(today).toString(),
                getString(repeats[subscription.repeat.ordinal()]), countdown);
    }

    private void subscriptionDetails(int index) {
        Subscription subscription = subscriptions.get(index);
        new AlertDialog.Builder(this).setTitle(subscription.name).setMessage(renewal(subscription))
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.edit, (d, w) -> editSubscription(index))
                .setNeutralButton(R.string.delete, (d, w) ->
                        new AlertDialog.Builder(this)
                                .setMessage(getString(R.string.delete_confirmation, subscription.name))
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.delete, (dialog, which) -> {
                                    List<Subscription> changed = new ArrayList<>(subscriptions);
                                    changed.remove(index);
                                    saveSubscriptions(changed);
                                }).show()).show();
    }

    private void editSubscription(int index) {
        if (index < 0 && subscriptions.size() >= Subscription.MAX_COUNT) {
            error(getString(R.string.subscription_limit));
            return;
        }
        LinearLayout form = form();
        EditText name = input(R.string.service_name);
        EditText date = input(R.string.renewal_hint);
        date.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        Spinner repeat = new Spinner(this);
        repeat.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.once), getString(R.string.monthly), getString(R.string.yearly)}));
        if (index >= 0) {
            Subscription existing = subscriptions.get(index);
            name.setText(existing.name);
            date.setText(existing.anchor.toString());
            repeat.setSelection(existing.repeat.ordinal());
        }
        form.addView(name);
        form.addView(date);
        form.addView(paragraph(getString(R.string.repeat_label)));
        form.addView(repeat);
        TextView problem = paragraph("");
        form.addView(problem);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(index < 0 ? R.string.add_subscription : R.string.edit)
                .setView(form).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Subscription entry;
            try {
                entry = new Subscription(name.getText().toString(),
                        LocalDate.parse(date.getText().toString().trim()),
                        Subscription.Repeat.values()[repeat.getSelectedItemPosition()]);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                problem.setText(R.string.invalid_subscription);
                return;
            }
            List<Subscription> changed = new ArrayList<>(subscriptions);
            if (index < 0) changed.add(entry);
            else changed.set(index, entry);
            if (saveSubscriptions(changed)) dialog.dismiss();
        }));
        dialog.show();
    }

    private boolean saveSubscriptions(List<Subscription> changed) {
        try {
            if (!prefs.edit().putString("subscriptions", Subscription.encode(changed)).commit()) {
                Log.e(TAG, "Subscription preferences commit failed");
                error(getString(R.string.subscription_write_error));
                return false;
            }
        } catch (JSONException | IllegalArgumentException e) {
            Log.e(TAG, "Could not encode subscriptions", e);
            error(getString(R.string.subscription_write_error));
            return false;
        }
        renderSubscriptions();
        return true;
    }

    private void renderAbout() {
        content.removeAllViews();
        content.addView(paragraph(getString(R.string.about_text)));
        content.addView(button(getString(R.string.tvmaze_link), () -> openLink("https://www.tvmaze.com/api")));
        content.addView(button(getString(R.string.license_link),
                () -> openLink("https://creativecommons.org/licenses/by-sa/4.0/")));
        content.addView(button(getString(R.string.apple_link),
                () -> openLink("https://performance-partners.apple.com/search-api")));
    }

    private void openLink(String link) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No listing handler", e);
            error(getString(R.string.source_unavailable, link));
        }
    }

    private void error(String message) {
        new AlertDialog.Builder(this).setTitle(R.string.error_title).setMessage(message)
                .setPositiveButton(R.string.close, null).show();
    }

    private LinearLayout form() {
        LinearLayout form = column();
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        return form;
    }

    private EditText input(int hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        return field;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private TextView paragraph(String value) {
        TextView text = text(value, 16, Color.LTGRAY);
        text.setPadding(0, dp(10), 0, dp(12));
        return text;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(17);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.card);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setOnClickListener(v -> action.run());
        button.setFocusable(true);
        return button;
    }

    private LinearLayout.LayoutParams weighted(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, 1);
        params.setMarginEnd(dp(10));
        params.bottomMargin = dp(10);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
