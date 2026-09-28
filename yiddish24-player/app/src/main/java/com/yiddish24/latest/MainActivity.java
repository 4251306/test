package com.yiddish24.latest;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String API = "https://www.yiddish24.com/ajax/get_category_audios.php";
    private static final String REFERER = "https://www.yiddish24.com/";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Item> items = new ArrayList<>();
    private ArrayAdapter<Item> adapter;
    private MediaPlayer player;
    private TextView statusText;
    private ProgressBar progressBar;
    private String categoryId = "57";
    private int playingId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.statusText);
        progressBar = findViewById(R.id.progressBar);
        ListView list = findViewById(R.id.itemList);

        adapter = new ArrayAdapter<Item>(this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Item item = getItem(position);
                TextView title = view.findViewById(android.R.id.text1);
                TextView sub = view.findViewById(android.R.id.text2);
                title.setText(item.title);
                title.setTextSize(16);
                String mark = item.file().exists() ? "Saved" : "Tap to download";
                if (playingId == item.id) {
                    mark = "Playing";
                }
                sub.setText(item.duration + "  ·  " + mark);
                return view;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> play(items.get(position)));

        findViewById(R.id.newsButton).setOnClickListener(v -> load("57", "News"));
        findViewById(R.id.videoButton).setOnClickListener(v -> load("224", "Videos"));
        findViewById(R.id.audioButton).setOnClickListener(v -> load("49", "Audio"));
        findViewById(R.id.stopButton).setOnClickListener(v -> stop());

        load("57", "News");
    }

    private void load(String catId, String label) {
        categoryId = catId;
        setStatus("Loading latest " + label + "…");
        executor.execute(() -> {
            try {
                List<Item> fresh = fetch(catId);
                runOnUiThread(() -> {
                    items.clear();
                    items.addAll(fresh);
                    adapter.notifyDataSetChanged();
                    setStatus(fresh.size() + " latest " + label.toLowerCase() + ". Tap one to download and play.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Could not load the list. " + e.getMessage()));
            }
        });
    }

    private List<Item> fetch(String catId) throws Exception {
        byte[] body = ("cat_id=" + URLEncoder.encode(catId, "UTF-8")).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = open(API, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        String json = readText(conn);
        conn.disconnect();
        JSONArray result = new JSONObject(json).getJSONArray("result");
        List<Item> list = new ArrayList<>();
        int limit = Math.min(result.length(), 20);
        for (int i = 0; i < limit; i++) {
            JSONObject row = result.getJSONObject(i);
            String url = row.optString("musicpath", "");
            if (!url.startsWith("https://") || !url.contains(".mp3")) {
                continue;
            }
            Item item = new Item();
            item.id = row.optInt("id");
            item.title = row.optString("title", "Untitled");
            item.duration = row.optString("musicduration", "");
            item.url = url;
            list.add(item);
        }
        return list;
    }

    private void play(Item item) {
        if (playingId == item.id) {
            stop();
            return;
        }
        stop();
        setStatus("Downloading…");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        executor.execute(() -> {
            try {
                File file = item.file();
                if (!file.exists() || file.length() < 1000) {
                    download(item.url, file);
                }
                runOnUiThread(() -> startPlayback(item, file));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    setStatus("Download failed. " + e.getMessage());
                });
            }
        });
    }

    private void startPlayback(Item item, File file) {
        try {
            progressBar.setVisibility(View.GONE);
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(mp -> {
                playingId = -1;
                adapter.notifyDataSetChanged();
                setStatus("Finished.");
            });
            player.prepare();
            player.start();
            playingId = item.id;
            adapter.notifyDataSetChanged();
            setStatus("Playing: " + item.title);
        } catch (Exception e) {
            setStatus("Could not play. " + e.getMessage());
        }
    }

    private void download(String url, File dest) throws Exception {
        HttpURLConnection conn = open(url, "GET");
        int length = conn.getContentLength();
        File parent = dest.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File tmp = new File(dest.getAbsolutePath() + ".part");
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int read;
            int done = 0;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                done += read;
                if (length > 0) {
                    int percent = (int) ((done * 100L) / length);
                    runOnUiThread(() -> progressBar.setProgress(percent));
                }
            }
        } finally {
            conn.disconnect();
        }
        if (tmp.length() < 1000) {
            tmp.delete();
            throw new IllegalStateException("File was empty");
        }
        if (dest.exists() && !dest.delete()) {
            throw new IllegalStateException("Could not replace old file");
        }
        if (!tmp.renameTo(dest)) {
            throw new IllegalStateException("Could not save file");
        }
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Referer", REFERER);
        return conn;
    }

    private String readText(HttpURLConnection conn) throws Exception {
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            StringBuilder text = new StringBuilder();
            int read;
            while ((read = in.read(buf)) != -1) {
                text.append(new String(buf, 0, read, StandardCharsets.UTF_8));
            }
            return text.toString();
        }
    }

    private void stop() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
        playingId = -1;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        setStatus("Stopped.");
    }

    private void setStatus(String text) {
        statusText.setText(text);
    }

    @Override
    protected void onDestroy() {
        stop();
        executor.shutdownNow();
        super.onDestroy();
    }

    private class Item {
        int id;
        String title;
        String duration;
        String url;

        File file() {
            File dir = getExternalFilesDir("audio");
            if (dir == null) {
                dir = getFilesDir();
            }
            return new File(dir, id + ".mp3");
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
