package com.yiddish24.latest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays remote media through localhost and keeps the bytes already heard.
 * A replay, or a resume, does not download those bytes again.
 */
public final class MediaRelay {
    static final long MAX_FILE_BYTES = 100L * 1024L * 1024L;
    private static final long PROBE_BYTES = 256L * 1024L;
    private static final String REFERER = "https://www.yiddish24.com/";

    private final File dir;
    private final long maxBytes;
    private final ConcurrentHashMap<String, String> urls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> cacheable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    private volatile boolean running;

    public MediaRelay(File dir, long maxBytes) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cache");
        }
    }

    public synchronized void start() throws Exception {
        if (running) {
            return;
        }
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread thread = new Thread(this::acceptLoop, "y24-relay");
        thread.setDaemon(true);
        thread.start();
    }

    public void shutdown() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
    }

    public File cachedFile(String remote) {
        File file = new File(dir, hash(remote));
        if (file.isFile() && file.length() > 0) {
            return file;
        }
        return null;
    }

    public String localUrl(String remote, boolean cache) {
        String hash = hash(remote);
        urls.put(hash, remote);
        cacheable.put(hash, cache);
        locks.putIfAbsent(hash, new Object());
        return "http://127.0.0.1:" + server.getLocalPort() + "/" + hash;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                pool.execute(() -> handle(socket));
            } catch (Exception e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            String header = readHeader(socket.getInputStream());
            String line = header.split("\r\n", 2)[0];
            String[] parts = line.split(" ");
            if (parts.length < 2) {
                socket.close();
                return;
            }
            String hash = parts[1];
            int slash = hash.lastIndexOf('/');
            if (slash >= 0) {
                hash = hash.substring(slash + 1);
            }
            int query = hash.indexOf('?');
            if (query >= 0) {
                hash = hash.substring(0, query);
            }
            String remote = urls.get(hash);
            if (remote == null) {
                writeRaw(socket, 404, "text/plain", null, null, "missing".getBytes(StandardCharsets.UTF_8));
                socket.close();
                return;
            }
            String range = headerValue(header, "Range");
            boolean cache = Boolean.TRUE.equals(cacheable.get(hash));
            if (!cache) {
                proxyOnly(socket, remote, range);
                return;
            }
            Object lock = locks.computeIfAbsent(hash, key -> new Object());
            synchronized (lock) {
                serveCached(socket, hash, remote, range);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void serveCached(Socket socket, String hash, String remote, String range) throws Exception {
        File complete = new File(dir, hash);
        File part = new File(dir, hash + ".part");
        long total = readTotal(new File(dir, hash + ".total"));
        if (complete.isFile() && complete.length() > 0) {
            serveFile(socket, complete, range, mime(remote), complete.length());
            return;
        }
        long have = part.isFile() ? part.length() : 0;
        long start = 0;
        long end = -1;
        boolean open = range == null;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).split(",")[0].trim();
            int dash = spec.indexOf('-');
            if (dash > 0) {
                start = Long.parseLong(spec.substring(0, dash));
            }
            String tail = dash >= 0 ? spec.substring(dash + 1).trim() : "";
            if (!tail.isEmpty()) {
                end = Long.parseLong(tail);
            } else {
                open = true;
            }
        }
        boolean tinyProbe = !open && end >= start && (end - start + 1) <= PROBE_BYTES && start >= have;
        if (tinyProbe || start > have) {
            proxyOnly(socket, remote, range);
            return;
        }
        if (!open && total > 0 && end < have) {
            serveFile(socket, part, range, mime(remote), total);
            return;
        }
        fillAndStream(socket, remote, part, complete, new File(dir, hash + ".total"), start, have, total);
    }

    private void fillAndStream(Socket socket, String remote, File part, File complete, File totalFile,
                               long start, long have, long total) throws Exception {
        HttpURLConnection connection = null;
        InputStream upstream = null;
        FileOutputStream append = null;
        try {
            if (have == 0 || have < total || total <= 0) {
                connection = open(remote);
                if (have > 0) {
                    connection.setRequestProperty("Range", "bytes=" + have + "-");
                }
                int code = connection.getResponseCode();
                String contentRange = connection.getHeaderField("Content-Range");
                long reported = totalBytes(contentRange, code, connection.getContentLengthLong());
                if (reported > 0) {
                    total = reported;
                    writeTotal(totalFile, total);
                }
                if (total > MAX_FILE_BYTES) {
                    proxyStream(socket, connection, code, contentRange);
                    return;
                }
                upstream = connection.getInputStream();
                if (code == 200 && have > 0) {
                    upstream.close();
                    upstream = null;
                    connection.disconnect();
                    connection = open(remote);
                    code = connection.getResponseCode();
                    upstream = connection.getInputStream();
                    have = 0;
                    part.delete();
                    total = connection.getContentLengthLong();
                    if (total > 0) {
                        writeTotal(totalFile, total);
                    }
                }
            }
            if (total <= 0) {
                if (connection != null) {
                    proxyStream(socket, connection, connection.getResponseCode(), connection.getHeaderField("Content-Range"));
                }
                return;
            }
            long end = total - 1;
            long count = end - start + 1;
            boolean partial = start > 0 || end < total - 1;
            OutputStream client = socket.getOutputStream();
            StringBuilder response = new StringBuilder();
            response.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
            response.append("Content-Type: ").append(mime(remote)).append("\r\n");
            response.append("Accept-Ranges: bytes\r\n");
            response.append("Content-Length: ").append(count).append("\r\n");
            if (partial) {
                response.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(total).append("\r\n");
            }
            response.append("Connection: close\r\n\r\n");
            client.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (have > start && part.isFile()) {
                try (FileInputStream in = new FileInputStream(part)) {
                    skipFully(in, start);
                    long left = Math.min(have, end + 1) - start;
                    copy(in, client, null, left);
                }
            }
            if (have < total && upstream != null) {
                append = new FileOutputStream(part, have > 0 && part.isFile());
                byte[] buffer = new byte[65536];
                int n;
                while ((n = upstream.read(buffer)) != -1) {
                    append.write(buffer, 0, n);
                    client.write(buffer, 0, n);
                }
                append.close();
                append = null;
            }
            client.flush();
            if (part.isFile() && part.length() == total) {
                if (!part.renameTo(complete)) {
                    part.delete();
                } else {
                    complete.setLastModified(System.currentTimeMillis());
                    totalFile.delete();
                    evict();
                }
            }
        } finally {
            if (append != null) {
                append.close();
            }
            if (upstream != null) {
                upstream.close();
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void proxyOnly(Socket socket, String remote, String range) throws Exception {
        HttpURLConnection connection = open(remote);
        if (range != null) {
            connection.setRequestProperty("Range", range);
        }
        int code = connection.getResponseCode();
        try {
            proxyStream(socket, connection, code, connection.getHeaderField("Content-Range"));
        } finally {
            connection.disconnect();
        }
    }

    private void proxyStream(Socket socket, HttpURLConnection connection, int code, String contentRange) throws Exception {
        long length = connection.getContentLengthLong();
        String type = connection.getContentType() == null ? "application/octet-stream" : connection.getContentType();
        OutputStream client = socket.getOutputStream();
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(code).append(code == 206 ? " Partial Content" : " OK").append("\r\n");
        response.append("Content-Type: ").append(type).append("\r\n");
        response.append("Accept-Ranges: bytes\r\n");
        response.append("Connection: close\r\n");
        if (length >= 0) {
            response.append("Content-Length: ").append(length).append("\r\n");
        }
        if (contentRange != null) {
            response.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        response.append("\r\n");
        client.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
        try (InputStream upstream = connection.getInputStream()) {
            copy(upstream, client, null, Long.MAX_VALUE);
        }
        client.flush();
    }

    private void serveFile(Socket socket, File file, String range, String type, long total) throws Exception {
        long len = file.length();
        long start = 0;
        long end = len - 1;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).split(",")[0].trim();
            int dash = spec.indexOf('-');
            if (dash > 0) {
                start = Long.parseLong(spec.substring(0, dash));
                String tail = spec.substring(dash + 1).trim();
                if (!tail.isEmpty()) {
                    end = Math.min(end, Long.parseLong(tail));
                }
            }
        }
        if (start < 0 || start >= len || end < start) {
            writeRaw(socket, 416, "text/plain", null, null, new byte[0]);
            return;
        }
        long count = end - start + 1;
        boolean partial = start > 0 || end < total - 1;
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        response.append("Content-Type: ").append(type).append("\r\n");
        response.append("Accept-Ranges: bytes\r\n");
        response.append("Content-Length: ").append(count).append("\r\n");
        if (partial) {
            response.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(total).append("\r\n");
        }
        response.append("Connection: close\r\n\r\n");
        OutputStream client = socket.getOutputStream();
        client.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
        try (FileInputStream in = new FileInputStream(partOr(file))) {
            skipFully(in, start);
            copy(in, client, null, count);
        }
        client.flush();
    }

    private static File partOr(File file) {
        return file;
    }

    private static void copy(InputStream in, OutputStream client, OutputStream extra, long max) throws Exception {
        byte[] buffer = new byte[65536];
        long left = max;
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) {
                return;
            }
            client.write(buffer, 0, n);
            if (extra != null) {
                extra.write(buffer, 0, n);
            }
            left -= n;
        }
    }

    private static void skipFully(InputStream in, long start) throws Exception {
        long skipped = 0;
        while (skipped < start) {
            long n = in.skip(start - skipped);
            if (n <= 0) {
                if (in.read() < 0) {
                    return;
                }
                n = 1;
            }
            skipped += n;
        }
    }

    private void evict() {
        File[] files = dir.listFiles((folder, name) -> !name.endsWith(".part") && !name.endsWith(".total"));
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long used = 0;
        for (File file : files) {
            used += file.length();
            if (used > maxBytes) {
                file.delete();
                new File(dir, file.getName() + ".part").delete();
                new File(dir, file.getName() + ".total").delete();
            }
        }
    }

    private static HttpURLConnection open(String remote) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(remote).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Referer", REFERER);
        return connection;
    }

    private static long readTotal(File file) {
        if (!file.isFile()) {
            return -1;
        }
        try {
            return Long.parseLong(new String(readAll(file), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] readAll(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out, null, Long.MAX_VALUE);
            return out.toByteArray();
        }
    }

    private static void writeTotal(File file, long total) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(Long.toString(total).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static long totalBytes(String contentRange, int code, long length) {
        if (contentRange != null) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0) {
                String tail = contentRange.substring(slash + 1).trim();
                if (!"*".equals(tail)) {
                    try {
                        return Long.parseLong(tail);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (code == 200) {
            return length;
        }
        return -1;
    }

    private static void writeRaw(Socket socket, int code, String type, String contentRange, Long length, byte[] body) throws Exception {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(code).append(" OK\r\nContent-Type: ").append(type).append("\r\n");
        response.append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\n\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String readHeader(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int a = 0;
        int b = 0;
        int c = 0;
        while (out.size() < 8192) {
            int d = in.read();
            if (d < 0) {
                break;
            }
            out.write(d);
            if (a == '\r' && b == '\n' && c == '\r' && d == '\n') {
                break;
            }
            a = b;
            b = c;
            c = d;
        }
        return out.toString("ISO-8859-1");
    }

    private static String headerValue(String header, String name) {
        String prefix = name.toLowerCase() + ":";
        for (String line : header.split("\r\n")) {
            if (line.toLowerCase().startsWith(prefix)) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return null;
    }

    static String mime(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".wav")) {
            return "audio/wav";
        }
        if (lower.contains(".mp4") || lower.contains(".m4a")) {
            return "video/mp4";
        }
        return "audio/mpeg";
    }

    static String hash(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", digest[i] & 0xff));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }
}
