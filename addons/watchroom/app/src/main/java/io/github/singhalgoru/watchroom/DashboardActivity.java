package io.github.singhalgoru.watchroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.Html;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class DashboardActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Catalog.Client client = new Catalog.Client();
    private final Runnable clockTick = this::updateClock;
    private SharedPreferences prefs;
    private TextView greeting, clock, date, temperature, conditions, discoveryStatus;
    private LinearLayout posters;
    private LinearLayout page, related;
    private LinearLayout appDock;
    private HorizontalScrollView relatedShelf;
    private TextView relatedLabel;
    private Button exploreButton;
    private Button browseButton;
    private TextView heroTitle, heroMeta, heroSummary, heroOutlet;
    private ImageView heroArt, pickerPoster;
    private final Map<Integer, Bitmap> artwork = new HashMap<>();
    private final Map<Integer, List<ImageView>> artworkViews = new HashMap<>();
    private List<Catalog.Title> displayed = new ArrayList<>();
    private int selected;
    private boolean picking;
    private boolean exploring;
    private Future<?> pending;
    private int generation;
    private boolean resumed, loading;
    private boolean needsLoad = true;
    private long lastLoaded;
    private DashboardWeather.Report lastWeather;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("watchroom", MODE_PRIVATE);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateClock();
        if (needsLoad || System.currentTimeMillis() - lastLoaded > 900_000) refresh();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (loading) needsLoad = true;
        loading = false;
        generation++;
        if (pending != null) pending.cancel(true);
        client.cancel();
        main.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void build() {
        picking = false;
        pickerPoster = null;
        artworkViews.clear();
        LinearLayout root = column();
        int safeX = Math.round(getResources().getDisplayMetrics().widthPixels * .05f);
        int safeY = Math.round(getResources().getDisplayMetrics().heightPixels * .05f);
        root.setPadding(safeX, safeY, safeX, safeY);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(8, 19, 25), Color.rgb(18, 20, 33), Color.rgb(31, 17, 28)}));
        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text(getString(R.string.cinema_heading), 13);
        brand.setLetterSpacing(.18f);
        toolbar.addView(brand, weighted(-1, 1));
        browseButton = button(R.string.browse_all, () -> browse(0, false));
        browseButton.setId(View.generateViewId());
        toolbar.addView(browseButton, sized(155, 32));
        exploreButton = button(exploring ? R.string.pick_tab : R.string.explore_catalog, () -> {
            if (exploring) showPicker();
            else {
                exploring = true;
                build();
                updateClock();
                refresh();
            }
        });
        exploreButton.setId(View.generateViewId());
        toolbar.addView(exploreButton, sized(116, 38));
        toolbar.addView(button(R.string.personalize, this::profile), sized(104, 32));
        toolbar.addView(button(R.string.refresh, this::refresh), sized(78, 32));
        toolbar.addView(button(R.string.about_dashboard, () -> browse(4, false)), sized(115, 32));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(44)));

        page = column();
        FrameLayout heroFrame = new FrameLayout(this);
        heroArt = new ImageView(this);
        heroArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroArt.setAlpha(.28f);
        heroFrame.addView(heroArt, new FrameLayout.LayoutParams(dp(390), -1, Gravity.RIGHT));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(12, 20, 28), Color.argb(190, 16, 20, 29), Color.TRANSPARENT}));
        heroFrame.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout hero = column();
        hero.setPadding(0, dp(12), 0, dp(8));
        heroOutlet = text("", 12);
        heroOutlet.setTextColor(Color.rgb(105, 223, 208));
        heroTitle = text(getString(R.string.dashboard_loading), 30);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroMeta = text("", 14);
        heroSummary = text("", 14);
        heroSummary.setMaxLines(2);
        heroSummary.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(heroOutlet);
        hero.addView(heroTitle);
        hero.addView(heroMeta);
        LinearLayout.LayoutParams summarySize = new LinearLayout.LayoutParams(-1, -2);
        summarySize.topMargin = dp(8);
        hero.addView(heroSummary, summarySize);
        heroFrame.addView(hero, new FrameLayout.LayoutParams(-1, -1));
        page.addView(heroFrame, new LinearLayout.LayoutParams(-1, dp(136)));
        discoveryStatus = text(getString(R.string.dashboard_loading), 12);
        page.addView(discoveryStatus);
        HorizontalScrollView shelf = shelf();
        posters = row();
        shelf.addView(posters);
        page.addView(shelf, new LinearLayout.LayoutParams(-1, dp(122)));
        relatedLabel = text(getString(R.string.related_heading), 12);
        page.addView(relatedLabel);
        relatedShelf = shelf();
        related = row();
        relatedShelf.addView(related);
        page.addView(relatedShelf, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        appDock = row();
        addApps(appDock);
        root.addView(appDock, new LinearLayout.LayoutParams(-1, dp(62)));
        LinearLayout dock = row();
        dock.setGravity(Gravity.CENTER_VERTICAL);
        greeting = text("", 11);
        clock = text("", 11);
        date = new TextView(this);
        temperature = text("", 10);
        conditions = text("", 11);
        greeting.setMaxLines(2);
        conditions.setMaxLines(2);
        clock.setSingleLine(true);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        temperature.setSingleLine(true);
        temperature.setGravity(Gravity.CENTER_VERTICAL);
        if (lastWeather != null) {
            temperature.setText(getString(R.string.temperature, lastWeather.celsius));
            conditions.setText(getString(R.string.weather_city,
                    getString(DashboardWeather.description(lastWeather.code)),
                    prefs.getString("profile_city", "")));
        }
        dock.addView(greeting, weighted(-2, 1));
        dock.addView(clock, sized(88, 34));
        dock.addView(temperature, sized(45, 34));
        dock.addView(conditions, weighted(-2, 1));
        root.addView(dock, new LinearLayout.LayoutParams(-1, dp(42)));
        setContentView(root);
        if (!exploring) showApps();
        else if (!displayed.isEmpty()) showTitles(displayed);
        else toolbar.getChildAt(1).requestFocus();
    }

    private HorizontalScrollView shelf() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(3), dp(3), dp(3), dp(3));
        return scroll;
    }

    private void updateClock() {
        main.removeCallbacks(clockTick);
        if (!resumed) return;
        Date now = new Date();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int label = hour < 12 ? R.string.greeting_morning
                : hour < 18 ? R.string.greeting_afternoon : R.string.greeting_evening;
        String name = prefs.getString("profile_name", "");
        greeting.setText(name.isEmpty() ? getString(label)
                : getString(R.string.personal_greeting, getString(label), name));
        clock.setText(DateFormat.getTimeFormat(this).format(now));
        date.setText(new java.text.SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        main.postDelayed(clockTick, 60_000 - System.currentTimeMillis() % 60_000);
    }

    private void refresh() {
        if (!resumed) return;
        if (picking) build();
        if (pending != null) pending.cancel(true);
        client.cancel();
        int ticket = ++generation;
        loading = true;
        needsLoad = false;
        boolean loadCatalog = exploring;
        if (loadCatalog) {
            heroMeta.setVisibility(View.VISIBLE);
            discoveryStatus.setText(R.string.dashboard_loading);
            heroTitle.setText(R.string.dashboard_loading);
            heroMeta.setText(R.string.imdb_unavailable);
            heroSummary.setText(R.string.catalog_not_personalized);
            heroArt.setImageDrawable(null);
            posters.removeAllViews();
            related.removeAllViews();
            displayed = new ArrayList<>();
            artwork.clear();
            artworkViews.clear();
        } else showApps();
        temperature.setText("");
        boolean configured = prefs.contains("profile_lat");
        conditions.setText(configured ? R.string.weather_loading : R.string.set_city);
        double latitude = Double.longBitsToDouble(prefs.getLong("profile_lat", 0));
        double longitude = Double.longBitsToDouble(prefs.getLong("profile_lon", 0));
        String city = prefs.getString("profile_city", "");
        pending = io.submit(() -> {
            if (configured) {
                try {
                    String json = new String(client.read(DashboardWeather.url(latitude, longitude), 100_000),
                            StandardCharsets.UTF_8);
                    DashboardWeather.Report report = DashboardWeather.parse(json);
                    post(ticket, () -> {
                        lastWeather = report;
                        temperature.setText(getString(R.string.temperature, report.celsius));
                        conditions.setText(getString(R.string.weather_city,
                                getString(DashboardWeather.description(report.code)), city));
                    });
                } catch (IOException | JSONException | IllegalArgumentException e) {
                    if (Thread.currentThread().isInterrupted()) return;
                    Log.w("Watchroom", "Dashboard weather failed", e);
                    post(ticket, () -> conditions.setText(R.string.weather_unavailable));
                }
            }
            if (!loadCatalog) {
                post(ticket, () -> {
                    loading = false;
                    lastLoaded = System.currentTimeMillis();
                });
                return;
            }
            try {
                List<Catalog.Title> found = client.search(Catalog.Kind.TV, "Dark");
                List<Catalog.Title> limited = new ArrayList<>(found.subList(0, Math.min(8, found.size())));
                post(ticket, () -> showTitles(limited));
                for (int i = 0; i < limited.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) return;
                    int position = i;
                    String address = limited.get(i).artwork;
                    if (address.isEmpty()) {
                        post(ticket, () -> artworkFailed(position));
                        continue;
                    }
                    try {
                        byte[] bytes = client.read(address, 300_000);
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                                || bounds.outWidth > 4096 || bounds.outHeight > 4096) {
                            throw new IOException("Invalid thumbnail dimensions");
                        }
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 1;
                        while (bounds.outWidth / options.inSampleSize > 256
                                || bounds.outHeight / options.inSampleSize > 384) options.inSampleSize *= 2;
                        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                        if (bitmap == null) throw new IOException("Could not decode thumbnail");
                        post(ticket, () -> {
                            artwork.put(position, bitmap);
                            List<ImageView> views = artworkViews.get(position);
                            if (views != null) for (ImageView view : views) view.setImageBitmap(bitmap);
                        });
                    } catch (IOException e) {
                        if (Thread.currentThread().isInterrupted()) return;
                        Log.w("Watchroom", "Thumbnail unavailable", e);
                        post(ticket, () -> artworkFailed(position));
                    }
                }
                post(ticket, () -> {
                    loading = false;
                    lastLoaded = System.currentTimeMillis();
                });
            } catch (IOException | JSONException | IllegalArgumentException e) {
                if (Thread.currentThread().isInterrupted()) return;
                Log.w("Watchroom", "Dashboard discovery failed", e);
                post(ticket, () -> {
                    loading = false;
                    discoveryStatus.setText(R.string.dashboard_load_error);
                });
            }
        });
    }

    private void post(int ticket, Runnable action) {
        main.post(() -> {
            if (resumed && !isDestroyed() && ticket == generation) action.run();
        });
    }

    private void showTitles(List<Catalog.Title> titles) {
        displayed = titles;
        discoveryStatus.setText(titles.isEmpty() ? getString(R.string.dashboard_load_error)
                : getString(R.string.top_picks_count, titles.size()));
        posters.removeAllViews();
        artworkViews.clear();
        for (int i = 0; i < titles.size(); i++) {
            posters.addView(poster(i, false), sized(91, 116));
        }
        if (!titles.isEmpty()) {
            selected = Math.min(selected, titles.size() - 1);
            selectTitle(selected);
            posters.getChildAt(selected).requestFocus();
        } else {
            heroTitle.setText(R.string.catalog_empty);
            heroMeta.setText(R.string.imdb_unavailable);
            heroSummary.setText(R.string.catalog_not_personalized);
        }
    }

    private void artworkFailed(int position) {
        List<ImageView> views = artworkViews.get(position);
        if (views != null) for (ImageView image : views) {
            image.setContentDescription(displayed.get(position).name + ": " + getString(R.string.art_unavailable));
        }
    }

    private ImageView image(int index) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(displayed.get(index).name);
        if (artwork.containsKey(index)) image.setImageBitmap(artwork.get(index));
        else image.setImageResource(R.drawable.icon);
        artworkViews.computeIfAbsent(index, k -> new ArrayList<>()).add(image);
        return image;
    }

    private View poster(int index, boolean compact) {
        Catalog.Title title = displayed.get(index);
        LinearLayout card = column();
        card.setPadding(dp(3), dp(3), dp(3), dp(3));
        card.addView(image(index), new LinearLayout.LayoutParams(-1, 0, 1));
        TextView label = text(title.name, compact ? 9 : 10);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(label, new LinearLayout.LayoutParams(-1, dp(16)));
        click(card, () -> details(title));
        card.setNextFocusUpId(exploreButton.getId());
        card.setOnFocusChangeListener((v, focus) -> { if (focus && !compact) selectTitle(index); });
        return card;
    }

    private void selectTitle(int index) {
        artworkViews.clear();
        for (int i = 0; i < posters.getChildCount(); i++) {
            LinearLayout card = (LinearLayout) posters.getChildAt(i);
            List<ImageView> views = new ArrayList<>();
            views.add((ImageView) card.getChildAt(0));
            artworkViews.put(i, views);
        }
        selected = index;
        Catalog.Title title = displayed.get(index);
        heroArt.setImageBitmap(artwork.get(index));
        artworkViews.get(index).add(heroArt);
        heroOutlet.setText(title.outlet.isEmpty() ? "TVMAZE" : title.outlet.toUpperCase(Locale.getDefault()));
        heroTitle.setText(title.name);
        heroMeta.setText(title.year + "  ·  " + title.genre + "  ·  "
                + getString(R.string.imdb_unavailable));
        heroSummary.setText(title.summary.isEmpty() ? getString(R.string.tv_notice)
                : Html.fromHtml(title.summary, Html.FROM_HTML_MODE_LEGACY).toString().trim());
        related.removeAllViews();
        String firstGenre = title.genre.split(",")[0].trim();
        for (int i = 0; i < displayed.size(); i++) {
            if (i != index && !firstGenre.isEmpty() && displayed.get(i).genre.contains(firstGenre)) {
                related.addView(poster(i, true), sized(85, 48));
            }
        }
    }

    private void details(Catalog.Title title) {
        new AlertDialog.Builder(this).setTitle(title.name)
                .setMessage(getString(R.string.imdb_unavailable) + "\n\n" + getString(R.string.tv_notice))
                .setPositiveButton(R.string.browse_all, (d, w) -> browse(0, false))
                .setNegativeButton(R.string.close, null).show();
    }

    private void showPicker() {
        if (displayed.isEmpty()) {
            error(getString(R.string.pick_first));
            return;
        }
        picking = true;
        artworkViews.clear();
        page.removeAllViews();
        TextView progress = text(getString(R.string.discover_progress, selected + 1, displayed.size()), 13);
        progress.setLetterSpacing(.13f);
        page.addView(progress);
        LinearLayout scene = row();
        LinearLayout copy = column();
        copy.setGravity(Gravity.BOTTOM);
        TextView name = text(displayed.get(selected).name, 33);
        copy.addView(name);
        TextView score = text(getString(R.string.imdb_unavailable), 14);
        copy.addView(score);
        copy.addView(text(getString(R.string.pick_remote_hint), 12));
        LinearLayout controls = row();
        Button pass = button(R.string.pass_pick, () -> advancePick(1));
        Button detail = button(R.string.source_details, () -> details(displayed.get(selected)));
        boolean liked = prefs.getStringSet("liked_titles", new HashSet<>()).contains(displayed.get(selected).link);
        Button like = button(liked ? R.string.liked_pick : R.string.like_pick, this::likePick);
        controls.addView(pass, sized(78, 42));
        controls.addView(detail, sized(135, 42));
        controls.addView(like, sized(78, 42));
        copy.addView(controls);
        scene.addView(copy, weighted(-1, .52f));
        FrameLayout stack = new FrameLayout(this);
        if (displayed.size() > 1) {
            ImageView behind = image((selected + 1) % displayed.size());
            behind.setAlpha(.35f);
            behind.setRotation(7);
            FrameLayout.LayoutParams backSize = new FrameLayout.LayoutParams(dp(190), dp(280), Gravity.CENTER);
            backSize.leftMargin = dp(60);
            stack.addView(behind, backSize);
        }
        ImageView front = image(selected);
        pickerPoster = front;
        front.setId(View.generateViewId());
        pass.setId(View.generateViewId());
        like.setNextFocusRightId(front.getId());
        pass.setNextFocusUpId(front.getId());
        detail.setNextFocusUpId(front.getId());
        like.setNextFocusUpId(front.getId());
        front.setNextFocusDownId(pass.getId());
        front.setRotation(-3);
        stack.addView(front, new FrameLayout.LayoutParams(dp(200), dp(300), Gravity.CENTER));
        front.setFocusable(true);
        front.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                advancePick(key == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                pickerPoster.requestFocus();
                return true;
            }
            return false;
        });
        click(front, () -> details(displayed.get(selected)));
        scene.addView(stack, weighted(-1, .48f));
        page.addView(scene, new LinearLayout.LayoutParams(-1, 0, 1));
        pass.requestFocus();
    }

    private void advancePick(int direction) {
        selected = (selected + direction + displayed.size()) % displayed.size();
        showPicker();
    }

    private void likePick() {
        Set<String> liked = new HashSet<>(prefs.getStringSet("liked_titles", new HashSet<>()));
        if (liked.size() >= 128 && !liked.contains(displayed.get(selected).link)) {
            error(getString(R.string.likes_full));
            return;
        }
        liked.add(displayed.get(selected).link);
        if (!prefs.edit().putStringSet("liked_titles", liked).commit()) {
            error(getString(R.string.like_failed));
            return;
        }
        advancePick(1);
    }

    @Override
    public void onBackPressed() {
        if (picking) {
            build();
            updateClock();
        } else if (exploring) {
            exploring = false;
            displayed = new ArrayList<>();
            artwork.clear();
            build();
            updateClock();
            refresh();
        } else super.onBackPressed();
    }

    private void browse(int tab, boolean pick) {
        startActivity(new Intent(this, WatchroomActivity.class).putExtra("tab", tab).putExtra("pick", pick));
    }

    private void showApps() {
        appDock.setVisibility(View.GONE);
        relatedLabel.setVisibility(View.GONE);
        relatedShelf.setVisibility(View.GONE);
        heroArt.setImageDrawable(null);
        heroOutlet.setText(R.string.apps_on_tv);
        heroTitle.setText(R.string.personalized_in_apps);
        heroMeta.setVisibility(View.GONE);
        heroSummary.setText(R.string.history_in_apps);
        discoveryStatus.setText(R.string.installed_apps_heading);
        posters.removeAllViews();
        addApps(posters);
        if (posters.getChildCount() == 0) discoveryStatus.setText(R.string.no_supported_apps);
    }

    private void addApps(LinearLayout dock) {
        addApp(dock, R.string.youtube, "com.google.android.youtube.tv");
        addApp(dock, R.string.netflix, "com.netflix.ninja");
        addApp(dock, R.string.hotstar, "in.startv.hotstar");
        addApp(dock, R.string.prime_video, "com.amazon.amazonvideo.livingroom");
    }

    private void addApp(LinearLayout dock, int label, String packageName) {
        Drawable logo;
        try {
            if (getPackageManager().getLeanbackLaunchIntentForPackage(packageName) == null
                    && getPackageManager().getLaunchIntentForPackage(packageName) == null) return;
            logo = getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w("Watchroom", "Optional app icon unavailable: " + packageName, e);
            return;
        }
        LinearLayout item = column();
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(8), dp(6), dp(8), dp(6));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(logo);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        item.addView(icon, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView name = text(getString(label), 12);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(name, new LinearLayout.LayoutParams(-1, dp(20)));
        item.setContentDescription(getString(label));
        click(item, () -> {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(packageName);
            try {
                if (launch == null) throw new ActivityNotFoundException(packageName);
                startActivity(launch);
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w("Watchroom", "Cannot open shortcut", e);
                error(getString(R.string.app_unavailable, getString(label)));
            }
        });
        if (dock == posters) item.setNextFocusUpId(browseButton.getId());
        dock.addView(item, dock == posters ? sized(176, 116) : weighted(-1, 1));
    }

    private void profile() {
        LinearLayout form = column();
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        EditText name = field(R.string.profile_name, prefs.getString("profile_name", ""), false);
        EditText city = field(R.string.profile_city, prefs.getString("profile_city", ""), false);
        EditText lat = field(R.string.latitude, prefs.contains("profile_lat")
                ? Double.toString(Double.longBitsToDouble(prefs.getLong("profile_lat", 0))) : "", true);
        EditText lon = field(R.string.longitude, prefs.contains("profile_lon")
                ? Double.toString(Double.longBitsToDouble(prefs.getLong("profile_lon", 0))) : "", true);
        form.addView(name);
        form.addView(city);
        form.addView(lat);
        form.addView(lon);
        TextView problem = text(getString(R.string.profile_hint), 13);
        form.addView(problem);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.personalize).setView(form)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double latitude, longitude;
            String person = name.getText().toString().trim();
            String place = city.getText().toString().trim();
            try {
                latitude = Double.parseDouble(lat.getText().toString().trim());
                longitude = Double.parseDouble(lon.getText().toString().trim());
                DashboardWeather.url(latitude, longitude);
                if (person.length() > 40 || place.isEmpty() || place.length() > 40) {
                    throw new IllegalArgumentException("Invalid name or city");
                }
            } catch (IllegalArgumentException e) {
                problem.setText(R.string.profile_error);
                return;
            }
            if (!prefs.edit().putString("profile_name", person).putString("profile_city", place)
                    .putLong("profile_lat", Double.doubleToLongBits(latitude))
                    .putLong("profile_lon", Double.doubleToLongBits(longitude)).commit()) {
                Log.e("Watchroom", "Dashboard profile persistence failed");
                problem.setText(R.string.profile_save_error);
                return;
            }
            dialog.dismiss();
            updateClock();
            refresh();
        }));
        dialog.show();
    }

    private EditText field(int hint, String value, boolean coordinate) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(coordinate ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED : InputType.TYPE_CLASS_TEXT);
        field.setText(value);
        return field;
    }

    private void error(String message) {
        new AlertDialog.Builder(this).setMessage(message).setPositiveButton(R.string.close, null).show();
    }

    private GradientDrawable shape(int color, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(16));
        shape.setStroke(dp(stroke), Color.argb(150, 216, 235, 246));
        return shape;
    }

    private void click(View view, Runnable action) {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, shape(Color.argb(170, 37, 83, 83), 2));
        background.addState(new int[0], shape(Color.argb(25, 90, 110, 130), 0));
        view.setBackground(background);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
    }

    private Button button(int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setPadding(dp(8), dp(3), dp(8), dp(3));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        click(button, action);
        return button;
    }

    private TextView text(String label, int size) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(size);
        return text;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBaselineAligned(false);
        return layout;
    }

    private LinearLayout.LayoutParams weighted(int height, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height < 0 ? height : dp(height), weight);
        params.setMarginEnd(dp(10));
        return params;
    }

    private LinearLayout.LayoutParams sized(int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(height));
        params.setMarginEnd(dp(8));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
