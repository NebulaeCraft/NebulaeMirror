package net.kuina.nebulaemirror.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * {@code <world>/nebulaemirror-bridge/}: {@code inbox/} (patches to apply, e.g. copied from the mirror's
 * zones/<name>/patch.nbt) and {@code jobs/<id>/} (patch.nbt, undo.nbt, job.json) for every job ever run.
 */
final class BridgeStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    final File root;
    final File inbox;
    final File jobs;

    BridgeStore(File worldDir) {
        root = new File(worldDir, "nebulaemirror-bridge");
        inbox = new File(root, "inbox");
        jobs = new File(root, "jobs");
        inbox.mkdirs();
        jobs.mkdirs();
    }

    /** A new job directory; ids sort by time: 20261108-101530-<zone>. */
    File newJob(String zone) {
        String base = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "-" + zone.replaceAll("[^a-zA-Z0-9_-]", "_");
        File d = new File(jobs, base);
        for (int i = 2; d.exists(); i++) {
            d = new File(jobs, base + "-" + i);
        }
        d.mkdirs();
        return d;
    }

    File job(String id) {
        return new File(jobs, id);
    }

    /** A patch file in the inbox (no paths: only names inside the inbox are accepted). */
    File inboxFile(String name) throws IOException {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IOException("请给出 " + inbox + " 中的文件名");
        }
        File f = new File(inbox, name);
        if (!f.isFile()) {
            throw new IOException("找不到 " + f);
        }
        return f;
    }

    static void copy(File from, File to) throws IOException {
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    void writeReport(Job job) {
        File f = new File(job.dir, "job.json");
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(f.toPath()), StandardCharsets.UTF_8)) {
            GSON.toJson(job.report(), w);
        } catch (IOException e) {
            MirrorBridge.LOG.warn("Cannot write {}: {}", f, e.toString());
        }
    }

    static JsonObject readReport(File jobDir) {
        File f = new File(jobDir, "job.json");
        try (Reader r = new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Newest first. */
    List<File> jobDirs() {
        File[] d = jobs.listFiles(File::isDirectory);
        List<File> out = new ArrayList<>(d == null ? new ArrayList<>() : Arrays.asList(d));
        out.sort((a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    /** Jobs that were checking or applying when the server stopped. */
    void markInterrupted() {
        for (File d : jobDirs()) {
            JsonObject o = readReport(d);
            if (o != null && ("check".equals(o.get("phase").getAsString()) || "apply".equals(o.get("phase").getAsString())
                    || "verify".equals(o.get("phase").getAsString()))) {
                o.addProperty("phase", "interrupted");
                File f = new File(d, "job.json");
                try (Writer w = new OutputStreamWriter(Files.newOutputStream(f.toPath()), StandardCharsets.UTF_8)) {
                    GSON.toJson(o, w);
                } catch (IOException e) {
                    MirrorBridge.LOG.warn("Cannot update {}: {}", f, e.toString());
                }
                MirrorBridge.LOG.warn("Job {} was interrupted by a server stop at entry {}; {}", d.getName(),
                        o.get("progress"), new File(d, "undo.nbt").isFile()
                                ? "/mirrorbridge rollback " + d.getName() + " restores the previous content"
                                : "nothing had been changed yet");
            }
        }
    }
}
