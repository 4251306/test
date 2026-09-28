package com.yiddish24.latest;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends android.app.Activity {
    static final String REFERER = "https://www.yiddish24.com/";
    private static final String API = "https://www.yiddish24.com/ajax/get_category_audios.php";
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/4251306/test/cursor/yiddish24-player-2b59/yiddish24-player/version.json";

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> images = new HashMap<>();
    private final List<Item> items = new ArrayList<>();

    private RowAdapter adapter;
    private MediaPlayer player;
    private TextView statusText;
    private TextView nowTitle;
    private TextView timeText;
    private SeekBar seekBar;
    private Button playPauseButton;
    private View playerBar;
    private ListView listView;
    private FrameLayout videoPanel;
    private SurfaceHolder surfaceHolder;
    private boolean surfaceReady;
    private boolean seeking;
    private String categoryId = "57";
    private Item pendingVideo;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                try {
                    int pos = player.getCurrentPosition();
                    int dur = player.getDuration();
                    if (!seeking && dur > 0) {
                        seekBar.setMax(dur);
                        seekBar.setProgress(pos);
                    }
                    timeText.setText(format(pos) + " / " + format(dur));
                } catch (IllegalStateException ignored) {
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        nowTitle = findViewById(R.id.nowTitle);
        timeText = findViewById(R.id.timeText);
        seekBar = findViewById(R.id.seekBar);
        playPauseButton = findViewById(R.id.playPauseButton);
        playerBar = findViewById(R.id.playerBar);
        listView = findViewById(R.id.itemList);
        videoPanel = findViewById(R.id.videoPanel);
        SurfaceView surfaceView = findViewById(R.id.videoSurface);

        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> play(items.get(position)));

        findViewById(R.id.newsButton).setOnClickListener(v -> select("57", R.id.newsButton));
        findViewById(R.id.videoButton).setOnClickListener(v -> select("247", R.id.videoButton));
        findViewById(R.id.audioButton).setOnClickListener(v -> select("49", R.id.audioButton));
        findViewById(R.id.updateButton).setOnClickListener(v -> checkUpdate(true));
        findViewById(R.id.closeVideoButton).setOnClickListener(v -> stopPlayback());
        playPauseButton.setOnClickListener(v -> toggle());

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                if (player != null && pendingVideo != null) {
                    player.setDisplay(holder);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                if (player != null) {
                    player.setDisplay(null);
                }
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (player != null) {
                    player.seekTo(bar.getProgress());
                }
            }
        });

        loadCategory();
        checkUpdate(false);
    }

    private void select(String id, int buttonId) {
        categoryId = id;
        styleTab(R.id.newsButton, buttonId == R.id.newsButton);
        styleTab(R.id.videoButton, buttonId == R.id.videoButton);
        styleTab(R.id.audioButton, buttonId == R.id.audioButton);
        loadCategory();
    }

    private void styleTab(int id, boolean active) {
        Button button = findViewById(id);
        button.setBackgroundColor(getColor(active ? R.color.orange : R.color.navy_deep));
        button.setTextColor(getColor(active ? android.R.color.white : R.color.blue_text));
    }

    private void loadCategory() {
        statusText.setText("Loading…");
        final String id = categoryId;
        executor.execute(() -> {
            try {
                List<Item> loaded = fetch(id);
                runOnUiThread(() -> {
                    if (!id.equals(categoryId)) {
                        return;
                    }
                    items.clear();
                    items.addAll(loaded);
                    adapter.notifyDataSetChanged();
                    statusText.setText(loaded.isEmpty() ? "Nothing new right now." : loaded.size() + " latest");
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Could not load. Check the internet and try again."));
            }
        });
    }

    private static String imageUrl(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
            return url;
        }
        return "";
    }

    private List<Item> fetch(String catId) throws Exception {
        byte[] body = ("cat_id=" + catId).getBytes();
        HttpURLConnection connection = open(API);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        JSONObject json = new JSONObject(read(connection));
        JSONArray result = json.getJSONArray("result");
        List<Item> loaded = new ArrayList<>();
        for (int i = 0; i < result.length(); i++) {
            JSONObject row = result.getJSONObject(i);
            String audio = row.optString("musicpath", "");
            String video = "";
            org.json.JSONArray pictures = row.optJSONArray("images");
            if (pictures != null) {
                for (int j = 0; j < pictures.length(); j++) {
                    String candidate = pictures.optString(j, "");
                    if (candidate.contains(".mp4")) {
                        video = candidate;
                    }
                }
            }
            boolean playVideo = "247".equals(catId) && !video.isEmpty();
            String url = playVideo ? video : audio;
            String title = row.optString("title", "").trim();
            String subtitle = row.optString("subtitle", "").trim();
            String catName = row.optString("cat_name", "").trim();
            if (!subtitle.isEmpty() && (title.isEmpty() || title.equals(catName))) {
                title = subtitle;
            }
            if (url.isEmpty() || title.isEmpty()) {
                continue;
            }
            Item item = new Item();
            item.title = title;
            item.url = url;
            item.image = imageUrl(row.optString("categoryimage", ""));
            String date = row.optString("created_date", "");
            String duration = row.optString("musicduration", "");
            item.meta = (date + "   " + duration).trim();
            item.video = playVideo;
            loaded.add(item);
        }
        return loaded;
    }

    private void play(Item item) {
        stopPlayback();
        pendingVideo = item.video ? item : null;
        videoPanel.setVisibility(item.video ? View.VISIBLE : View.GONE);
        listView.setVisibility(item.video ? View.GONE : View.VISIBLE);
        playerBar.setVisibility(View.VISIBLE);
        nowTitle.setText(item.title);
        playPauseButton.setText("…");
        statusText.setText("Playing…");

        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(item.video ? AudioAttributes.CONTENT_TYPE_MOVIE : AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        if (item.video && surfaceReady) {
            player.setDisplay(surfaceHolder);
        }
        player.setOnPreparedListener(mp -> {
            if (item.video && surfaceReady) {
                mp.setDisplay(surfaceHolder);
            }
            mp.start();
            playPauseButton.setText("Pause");
            handler.removeCallbacks(tick);
            handler.post(tick);
        });
        player.setOnCompletionListener(mp -> playPauseButton.setText("Play"));
        player.setOnErrorListener((mp, what, extra) -> {
            statusText.setText("This clip did not start. Tap another one.");
            playPauseButton.setText("Play");
            return true;
        });
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", REFERER);
            headers.put("User-Agent", "Mozilla/5.0");
            player.setDataSource(this, Uri.parse(item.url), headers);
            player.prepareAsync();
        } catch (Exception e) {
            statusText.setText("This clip did not start. Tap another one.");
        }
    }

    private void toggle() {
        if (player == null) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                playPauseButton.setText("Play");
            } else {
                player.start();
                playPauseButton.setText("Pause");
            }
        } catch (IllegalStateException e) {
            statusText.setText("This clip did not start. Tap another one.");
        }
    }

    private void stopPlayback() {
        handler.removeCallbacks(tick);
        pendingVideo = null;
        if (player != null) {
            player.release();
            player = null;
        }
        videoPanel.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
        playerBar.setVisibility(View.GONE);
    }

    private void checkUpdate(boolean manual) {
        statusText.setText(manual ? "Checking for an update…" : statusText.getText());
        executor.execute(() -> {
            try {
                HttpURLConnection connection = open(VERSION_URL);
                JSONObject json = new JSONObject(read(connection));
                int remote = json.getInt("versionCode");
                int local = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                String apkUrl = json.getString("apkUrl");
                if (remote <= local) {
                    if (manual) {
                        runOnUiThread(() -> {
                            statusText.setText("You already have the latest.");
                            Toast.makeText(this, "You already have the latest.", Toast.LENGTH_SHORT).show();
                        });
                    }
                    return;
                }
                runOnUiThread(() -> statusText.setText("Downloading the new version…"));
                File dir = new File(getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("cache");
                }
                File apk = new File(dir, "update.apk");
                download(apkUrl, apk);
                runOnUiThread(() -> install(apk));
            } catch (Exception e) {
                if (manual) {
                    runOnUiThread(() -> statusText.setText("Update check failed. Try again later."));
                }
            }
        });
    }

    private void install(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            statusText.setText("Allow this app to install updates, then tap Update again.");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        try {
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try {
                try (InputStream in = new java.io.FileInputStream(apk);
                     OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    session.fsync(out);
                }
                Intent callback = new Intent(this, UpdateReceiver.class);
                PendingIntent pending = PendingIntent.getBroadcast(
                        this, sessionId, callback, PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(pending.getIntentSender());
            } finally {
                session.close();
            }
            statusText.setText("Confirm the install on the next screen.");
        } catch (Exception e) {
            statusText.setText("Could not start the update. Tap Update to try again.");
        }
    }

    private Bitmap image(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        synchronized (images) {
            if (images.containsKey(url)) {
                return images.get(url);
            }
        }
        try {
            HttpURLConnection connection = open(url);
            Bitmap bitmap = BitmapFactory.decodeStream(connection.getInputStream());
            connection.disconnect();
            if (bitmap != null) {
                synchronized (images) {
                    if (images.size() > 40) {
                        images.clear();
                    }
                    images.put(url, bitmap);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Referer", REFERER);
        return connection;
    }

    private static String read(HttpURLConnection connection) throws Exception {
        try (InputStream in = connection.getInputStream()) {
            byte[] buffer = new byte[4096];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static void download(String url, File dest) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static String format(int ms) {
        if (ms < 0) {
            ms = 0;
        }
        int total = ms / 1000;
        return (total / 60) + ":" + String.format("%02d", total % 60);
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        executor.shutdownNow();
        super.onDestroy();
    }

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View row = convert == null
                    ? getLayoutInflater().inflate(R.layout.item_row, parent, false)
                    : convert;
            Item item = items.get(position);
            TextView title = row.findViewById(R.id.rowTitle);
            TextView meta = row.findViewById(R.id.rowMeta);
            ImageView thumb = row.findViewById(R.id.thumb);
            title.setText(item.title);
            meta.setText(item.meta);
            thumb.setImageBitmap(null);
            thumb.setTag(item.image);
            executor.execute(() -> {
                Bitmap bitmap = image(item.image);
                runOnUiThread(() -> {
                    if (item.image.equals(thumb.getTag())) {
                        thumb.setImageBitmap(bitmap);
                    }
                });
            });
            return row;
        }
    }

    static class Item {
        String title;
        String url;
        String image;
        String meta;
        boolean video;
    }
}
