package com.yiddish24.latest;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends android.app.Activity {
    private static final String API = "https://www.yiddish24.com/ajax/get_category_audios.php";
    private static final String REFERER = "https://www.yiddish24.com/";
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/4251306/test/cursor/yiddish24-player-2b59/yiddish24-player/version.json";
    private static final long LIST_FRESH_MS = 3L * 60L * 60L * 1000L;
    private static final long IMAGE_CAP = 30L * 1024L * 1024L;
    private static final long MEDIA_CAP = 300L * 1024L * 1024L;

    private enum Screen { HOME, SUBS, ITEMS }

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory = new LruCache<>(32);
    private final List<Section> sections = new ArrayList<>();
    private final List<Clip> clips = new ArrayList<>();

    private Screen screen = Screen.HOME;
    private Section currentSection;
    private String currentSubId = "";
    private RowAdapter adapter;
    private MediaRelay relay;
    private MediaPlayer player;
    private TextView statusText;
    private TextView screenTitle;
    private TextView nowTitle;
    private TextView timeText;
    private Button backButton;
    private SeekBar seekBar;
    private Button playPauseButton;
    private View playerBar;
    private ListView listView;
    private FrameLayout videoPanel;
    private SurfaceHolder surfaceHolder;
    private boolean surfaceReady;
    private boolean seeking;
    private boolean playingVideo;
    private File listDir;
    private File imageDir;

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
                    timeText.setText(format(pos) + " / " + format(Math.max(dur, 0)));
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
        listDir = new File(getCacheDir(), "lists");
        imageDir = new File(getCacheDir(), "images");
        listDir.mkdirs();
        imageDir.mkdirs();
        try {
            relay = new MediaRelay(new File(getCacheDir(), "media"), MEDIA_CAP);
            relay.start();
        } catch (Exception e) {
            relay = null;
        }

        statusText = findViewById(R.id.statusText);
        screenTitle = findViewById(R.id.screenTitle);
        nowTitle = findViewById(R.id.nowTitle);
        timeText = findViewById(R.id.timeText);
        backButton = findViewById(R.id.backButton);
        seekBar = findViewById(R.id.seekBar);
        playPauseButton = findViewById(R.id.playPauseButton);
        playerBar = findViewById(R.id.playerBar);
        listView = findViewById(R.id.itemList);
        videoPanel = findViewById(R.id.videoPanel);
        SurfaceView surfaceView = findViewById(R.id.videoSurface);

        loadMenu();
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> onRow(position));
        backButton.setOnClickListener(v -> goBack());
        findViewById(R.id.updateButton).setOnClickListener(v -> checkUpdate(true));
        findViewById(R.id.closeVideoButton).setOnClickListener(v -> stopPlayback());
        playPauseButton.setOnClickListener(v -> toggle());

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                if (player != null && playingVideo) {
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
                    try {
                        player.seekTo(bar.getProgress());
                    } catch (IllegalStateException ignored) {
                    }
                }
            }
        });
        showHome();
        checkUpdate(false);
    }

    private void loadMenu() {
        try {
            InputStream in = getResources().openRawResource(R.raw.menu);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            in.close();
            JSONArray array = new JSONArray(out.toString("UTF-8"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                Section section = new Section();
                section.id = row.getString("id");
                section.name = row.getString("name");
                section.color = row.getString("color");
                JSONArray subs = row.getJSONArray("subs");
                for (int j = 0; j < subs.length(); j++) {
                    JSONObject sub = subs.getJSONObject(j);
                    Sub item = new Sub();
                    item.id = sub.getString("id");
                    item.name = sub.getString("name");
                    item.image = sub.optString("image", "");
                    section.subs.add(item);
                }
                sections.add(section);
            }
        } catch (Exception e) {
            statusText.setText("Could not read the section list.");
        }
    }

    private void showHome() {
        screen = Screen.HOME;
        currentSection = null;
        currentSubId = "";
        screenTitle.setText("אפטיילונגען");
        screenTitle.setTextColor(getColor(R.color.navy));
        backButton.setVisibility(View.GONE);
        statusText.setText("Tap a section. Saved lists stay on the phone.");
        adapter.notifyDataSetChanged();
        listView.setSelection(0);
    }

    private void showSubs(Section section) {
        currentSection = section;
        screen = Screen.SUBS;
        currentSubId = "";
        screenTitle.setText(section.name);
        screenTitle.setTextColor(Color.parseColor(section.color));
        backButton.setVisibility(View.VISIBLE);
        statusText.setText("Pictures are saved after the first open.");
        adapter.notifyDataSetChanged();
        listView.setSelection(0);
    }

    private void openSub(Sub sub) {
        screen = Screen.ITEMS;
        currentSubId = sub.id;
        screenTitle.setText(sub.name);
        if (currentSection != null) {
            screenTitle.setTextColor(Color.parseColor(currentSection.color));
        }
        backButton.setVisibility(View.VISIBLE);
        clips.clear();
        adapter.notifyDataSetChanged();
        String cached = readList(sub.id);
        boolean fresh = listFresh(sub.id);
        if (cached != null) {
            showClips(sub.id, cached, true);
        } else {
            statusText.setText("Loading…");
        }
        if (cached != null && fresh) {
            return;
        }
        executor.execute(() -> {
            try {
                String body = fetchList(sub.id);
                writeList(sub.id, body);
                runOnUiThread(() -> showClips(sub.id, body, false));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (sub.id.equals(currentSubId) && clips.isEmpty()) {
                        statusText.setText("Could not load. Check the internet and try again.");
                    }
                });
            }
        });
    }

    private void showClips(String subId, String body, boolean fromCache) {
        if (!subId.equals(currentSubId) || screen != Screen.ITEMS) {
            return;
        }
        try {
            clips.clear();
            clips.addAll(parseClips(body));
            adapter.notifyDataSetChanged();
            if (fromCache) {
                statusText.setText(clips.size() + " saved on the phone.");
            } else {
                statusText.setText(clips.size() + " latest. Saved for next time.");
            }
        } catch (Exception e) {
            statusText.setText("Could not read this section.");
        }
    }

    private void onRow(int position) {
        if (screen == Screen.HOME) {
            showSubs(sections.get(position));
        } else if (screen == Screen.SUBS && currentSection != null) {
            openSub(currentSection.subs.get(position));
        } else if (position >= 0 && position < clips.size()) {
            play(clips.get(position), false);
        }
    }

    private void goBack() {
        if (videoPanel.getVisibility() == View.VISIBLE) {
            stopPlayback();
            return;
        }
        if (screen == Screen.ITEMS && currentSection != null) {
            showSubs(currentSection);
        } else {
            showHome();
        }
    }

    @Override
    public void onBackPressed() {
        if (screen != Screen.HOME || videoPanel.getVisibility() == View.VISIBLE) {
            goBack();
            return;
        }
        super.onBackPressed();
    }

    private void play(Clip clip, boolean video) {
        String url = video ? clip.video : clip.audio;
        if (url == null || url.isEmpty()) {
            url = clip.audio != null && !clip.audio.isEmpty() ? clip.audio : clip.video;
            video = url != null && url.contains(".mp4");
        }
        if (url == null || url.isEmpty()) {
            return;
        }
        final String playUrl = url;
        final boolean asVideo = video;
        stopPlayback();
        playingVideo = asVideo;
        videoPanel.setVisibility(asVideo ? View.VISIBLE : View.GONE);
        listView.setVisibility(asVideo ? View.GONE : View.VISIBLE);
        playerBar.setVisibility(View.VISIBLE);
        nowTitle.setText(clip.title);
        playPauseButton.setText("…");

        boolean live = clip.live || isLive(playUrl);
        File saved = !asVideo && !live && relay != null ? relay.cachedFile(playUrl) : null;
        if (asVideo) {
            statusText.setText("Video uses a lot of data and is not saved.");
        } else if (live) {
            statusText.setText("Live");
        } else if (saved != null) {
            statusText.setText("Playing saved copy.");
        } else {
            statusText.setText("Playing. This copy is being saved.");
        }

        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(asVideo ? AudioAttributes.CONTENT_TYPE_MOVIE : AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        if (asVideo && surfaceReady) {
            player.setDisplay(surfaceHolder);
        }
        player.setOnPreparedListener(mp -> {
            if (asVideo && surfaceReady) {
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
            if (saved != null) {
                player.setDataSource(saved.getAbsolutePath());
            } else if (!asVideo && !live && relay != null) {
                Map<String, String> headers = new HashMap<>();
                player.setDataSource(this, Uri.parse(relay.localUrl(playUrl, true)), headers);
            } else {
                Map<String, String> headers = new HashMap<>();
                headers.put("Referer", REFERER);
                headers.put("User-Agent", "Mozilla/5.0");
                player.setDataSource(this, Uri.parse(playUrl), headers);
            }
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
        playingVideo = false;
        if (player != null) {
            player.release();
            player = null;
        }
        videoPanel.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
        playerBar.setVisibility(View.GONE);
    }

    private List<Clip> parseClips(String body) throws Exception {
        JSONArray result = new JSONObject(body).getJSONArray("result");
        List<Clip> loaded = new ArrayList<>();
        for (int i = 0; i < result.length(); i++) {
            JSONObject row = result.getJSONObject(i);
            String audio = row.optString("musicpath", "");
            String video = "";
            String image = "";
            JSONArray pictures = row.optJSONArray("images");
            if (pictures != null) {
                for (int j = 0; j < pictures.length(); j++) {
                    String candidate = pictures.optString(j, "");
                    if (candidate.contains(".mp4")) {
                        video = candidate;
                    } else if (image.isEmpty() && isImage(candidate)) {
                        image = candidate;
                    }
                }
            }
            if (image.isEmpty()) {
                image = imageUrl(row.optString("categoryimage", ""));
            }
            String title = row.optString("title", "").trim();
            String subtitle = row.optString("subtitle", "").trim();
            String catName = row.optString("cat_name", "").trim();
            if (!subtitle.isEmpty() && (title.isEmpty() || title.equals(catName))) {
                title = subtitle;
            }
            if (title.isEmpty() || (audio.isEmpty() && video.isEmpty())) {
                continue;
            }
            Clip clip = new Clip();
            clip.title = title;
            clip.audio = audio;
            clip.video = video;
            clip.image = image;
            clip.live = "stream".equals(row.optString("type")) || isLive(audio);
            String date = row.optString("created_date", "");
            String duration = row.optString("musicduration", "");
            clip.meta = clip.live ? "Live" : (date + "   " + duration).trim();
            loaded.add(clip);
        }
        return loaded;
    }

    private static boolean isLive(String url) {
        return url != null && (url.contains("y24.app") || url.contains("live.yiddish24.com"));
    }

    private static boolean isImage(String url) {
        String lower = url.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private static String imageUrl(String url) {
        return isImage(url) ? url : "";
    }

    private String readList(String id) {
        File file = listFile(id);
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return new String(readFile(file), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean listFresh(String id) {
        File file = listFile(id);
        return file != null && file.isFile() && System.currentTimeMillis() - file.lastModified() < LIST_FRESH_MS;
    }

    private void writeList(String id, String body) throws Exception {
        File file = listFile(id);
        if (file == null) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private File listFile(String id) {
        if (id == null || !id.matches("\\d+")) {
            return null;
        }
        return new File(listDir, id + ".json");
    }

    private String fetchList(String id) throws Exception {
        byte[] body = ("cat_id=" + id).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(API);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        return read(connection);
    }

    private Bitmap bitmap(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Bitmap cached = memory.get(url);
        if (cached != null) {
            return cached;
        }
        File file = new File(imageDir, MediaRelay.hash(url));
        if (!file.isFile()) {
            try {
                HttpURLConnection connection = open(url);
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                }
                connection.disconnect();
                trimImages();
            } catch (Exception e) {
                file.delete();
                return null;
            }
        }
        Bitmap decoded = decode(file);
        if (decoded != null) {
            memory.put(url, decoded);
        }
        return decoded;
    }

    private static Bitmap decode(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 180 && bounds.outHeight / options.inSampleSize > 180) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void trimImages() {
        File[] files = imageDir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long used = 0;
        for (File file : files) {
            used += file.length();
            if (used > IMAGE_CAP) {
                file.delete();
            }
        }
    }

    private void checkUpdate(boolean manual) {
        if (manual) {
            statusText.setText("Checking for an update…");
        }
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
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
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

    private void bindImage(ImageView view, String url) {
        view.setImageBitmap(null);
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            return;
        }
        Bitmap ready = memory.get(url);
        if (ready != null) {
            view.setImageBitmap(ready);
            return;
        }
        executor.execute(() -> {
            Bitmap bitmap = bitmap(url);
            runOnUiThread(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
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
            return new String(readStream(in), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readFile(File file) throws Exception {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return readStream(in);
        }
    }

    private static byte[] readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void download(String url, File dest) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
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
        if (relay != null) {
            relay.shutdown();
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            if (screen == Screen.HOME) {
                return sections.size();
            }
            if (screen == Screen.SUBS && currentSection != null) {
                return currentSection.subs.size();
            }
            if (screen == Screen.ITEMS) {
                return clips.size();
            }
            return 0;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            return screen.ordinal();
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            int type = getItemViewType(position);
            if (convert != null && !(convert.getTag() instanceof Integer && (Integer) convert.getTag() == type)) {
                convert = null;
            }
            if (type == Screen.HOME.ordinal()) {
                View row = convert == null ? getLayoutInflater().inflate(R.layout.section_row, parent, false) : convert;
                row.setTag(type);
                Section section = sections.get(position);
                TextView name = row.findViewById(R.id.sectionName);
                TextView count = row.findViewById(R.id.sectionCount);
                View stripe = row.findViewById(R.id.stripe);
                name.setText(section.name);
                count.setText(section.subs.size() + " אפטיילונגען");
                stripe.setBackgroundColor(Color.parseColor(section.color));
                return row;
            }
            if (type == Screen.SUBS.ordinal()) {
                View row = convert == null ? getLayoutInflater().inflate(R.layout.sub_row, parent, false) : convert;
                row.setTag(type);
                Sub sub = currentSection.subs.get(position);
                ((TextView) row.findViewById(R.id.subName)).setText(sub.name);
                bindImage(row.findViewById(R.id.subThumb), sub.image);
                return row;
            }
            View row = convert == null ? getLayoutInflater().inflate(R.layout.item_row, parent, false) : convert;
            row.setTag(type);
            Clip clip = clips.get(position);
            ((TextView) row.findViewById(R.id.rowTitle)).setText(clip.title);
            ((TextView) row.findViewById(R.id.rowMeta)).setText(clip.meta);
            bindImage(row.findViewById(R.id.thumb), clip.image);
            TextView video = row.findViewById(R.id.videoMark);
            boolean hasVideo = clip.video != null && !clip.video.isEmpty();
            video.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
            video.setOnClickListener(hasVideo ? v -> play(clip, true) : null);
            return row;
        }
    }

    static class Section {
        String id;
        String name;
        String color;
        final List<Sub> subs = new ArrayList<>();
    }

    static class Sub {
        String id;
        String name;
        String image;
    }

    static class Clip {
        String title;
        String audio;
        String video;
        String image;
        String meta;
        boolean live;
    }
}
