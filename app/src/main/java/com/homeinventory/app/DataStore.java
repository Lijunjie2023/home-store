package com.homeinventory.app;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

final class DataStore {
  final Context context;
  final File photos;
  private final AtomicFile data;

  DataStore(Context c) {
    context = c;
    photos = new File(c.getFilesDir(), "photos");
    photos.mkdirs();
    data = new AtomicFile(new File(c.getFilesDir(), "inventory.json"));
  }

  Inventory load() throws Exception {
    if (!data.getBaseFile().exists() && !new File(data.getBaseFile() + ".bak").exists()) {
      Inventory fresh = new Inventory();
      for (String n : Arrays.asList("客厅", "卧室", "儿童房", "储藏室")) fresh.nameSpace(null, n);
      save(fresh);
      return fresh;
    }
    try (InputStream in = data.openRead()) {
      return decode(new String(readLimited(in, 10_000_000), StandardCharsets.UTF_8));
    }
  }

  static String encode(Inventory d) throws JSONException {
    JSONObject root = new JSONObject().put("version", 1);
    JSONArray ss = new JSONArray(), cs = new JSONArray(), is = new JSONArray();
    for (Inventory.Space s : d.spaces) ss.put(new JSONObject().put("id", s.id).put("name", s.name));
    for (Inventory.Container c : d.containers)
      cs.put(
          new JSONObject()
              .put("id", c.id)
              .put("space", c.spaceId)
              .put("name", c.name)
              .put("note", c.note)
              .put("art", c.art)
              .put("photo", c.photo == null ? "" : c.photo));
    for (Inventory.Item i : d.items)
      is.put(
          new JSONObject()
              .put("id", i.id)
              .put("container", i.containerId)
              .put("name", i.name)
              .put("category", i.category)
              .put("note", i.note)
              .put("quantity", i.quantity)
              .put("created", i.created)
              .put("photos", new JSONArray(i.photos)));
    return root.put("spaces", ss).put("containers", cs).put("items", is).toString();
  }

  static Inventory decode(String s) throws Exception {
    JSONObject root = new JSONObject(s);
    if (root.getInt("version") != 1) throw new IOException("不支持的备份版本");
    Inventory d = new Inventory();
    JSONArray a = root.getJSONArray("spaces");
    for (int n = 0; n < a.length(); n++) {
      JSONObject o = a.getJSONObject(n);
      d.spaces.add(new Inventory.Space(o.getString("id"), o.getString("name")));
    }
    a = root.getJSONArray("containers");
    for (int n = 0; n < a.length(); n++) {
      JSONObject o = a.getJSONObject(n);
      d.containers.add(
          new Inventory.Container(
              o.getString("id"),
              o.getString("space"),
              o.getString("name"),
              o.getString("note"),
              o.getString("art"),
              o.optString("photo", "")));
    }
    a = root.getJSONArray("items");
    for (int n = 0; n < a.length(); n++) {
      JSONObject o = a.getJSONObject(n);
      List<String> ps = new ArrayList<>();
      JSONArray p = o.getJSONArray("photos");
      for (int k = 0; k < p.length(); k++) ps.add(p.getString(k));
      d.items.add(
          new Inventory.Item(
              o.getString("id"),
              o.getString("container"),
              o.getString("name"),
              o.getString("category"),
              o.getString("note"),
              o.getInt("quantity"),
              o.getLong("created"),
              ps));
    }
    d.validate();
    return d;
  }

  void save(Inventory inv) throws Exception {
    inv.validate();
    byte[] bytes = encode(inv).getBytes(StandardCharsets.UTF_8);
    FileOutputStream out = null;
    try {
      out = data.startWrite();
      out.write(bytes);
      data.finishWrite(out);
    } catch (Exception e) {
      if (out != null) data.failWrite(out);
      throw e;
    }
  }

  String importPhoto(Uri uri) throws Exception {
    ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
    Bitmap bitmap =
        ImageDecoder.decodeBitmap(
            source,
            (decoder, info, src) -> {
              int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
              double scale = Math.min(1, 1600.0 / max);
              decoder.setTargetSize(
                  Math.max(1, (int) (info.getSize().getWidth() * scale)),
                  Math.max(1, (int) (info.getSize().getHeight() * scale)));
              decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
    String name = Inventory.id() + ".jpg";
    File target = new File(photos, name);
    try (FileOutputStream out = new FileOutputStream(target)) {
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("无法保存照片");
    } catch (Exception e) {
      target.delete();
      throw e;
    } finally {
      bitmap.recycle();
    }
    return name;
  }

  void exportZip(Uri uri, Inventory snapshot) throws Exception {
    byte[] metadata = encode(snapshot).getBytes(StandardCharsets.UTF_8);
    Set<String> files = snapshot.photoFiles();
    if (metadata.length > 10_000_000 || files.size() + 1 > 10_000) throw new IOException("备份条目过多");
    long total = metadata.length;
    for (String name : files) {
      File file = new File(photos, name);
      if (!file.isFile() || file.length() > 20_000_000) throw new IOException("备份照片缺失或过大");
      total += file.length();
    }
    if (total > 500_000_000) throw new IOException("备份超过当前版本支持的大小");
    try (OutputStream raw = context.getContentResolver().openOutputStream(uri, "wt")) {
      if (raw == null) throw new IOException("无法写入备份文件");
      try (ZipOutputStream zip = new ZipOutputStream(raw)) {
        zip.putNextEntry(new ZipEntry("inventory.json"));
        zip.write(metadata);
        zip.closeEntry();
        for (String name : snapshot.photoFiles()) {
          zip.putNextEntry(new ZipEntry("photos/" + name));
          Files.copy(new File(photos, name).toPath(), zip);
          zip.closeEntry();
        }
      }
    }
  }

  // Stage and validate the complete archive before touching the current inventory.
  Inventory restoreZip(Uri uri) throws Exception {
    File staging = new File(context.getCacheDir(), "restore-" + Inventory.id());
    staging.mkdirs();
    Set<String> seen = new HashSet<>();
    long total = 0;
    String json = null;
    try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
      if (raw == null) throw new IOException("无法读取备份");
      try (ZipInputStream zip = new ZipInputStream(raw)) {
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
          String name = e.getName();
          if (seen.size() >= 10000 || !seen.add(name)) throw new IOException("备份条目过多或重复");
          if (name.equals("inventory.json")) {
            byte[] bytes = readLimited(zip, 10_000_000);
            total += bytes.length;
            json = new String(bytes, StandardCharsets.UTF_8);
          } else if (name.startsWith("photos/")) {
            String file = name.substring(7);
            Inventory.checkPhoto(file, false);
            File dest = new File(staging, file);
            try (OutputStream out = new FileOutputStream(dest)) {
              byte[] buffer = new byte[16384];
              int read;
              long count = 0;
              while ((read = zip.read(buffer)) != -1) {
                count += read;
                total += read;
                if (count > 20_000_000 || total > 500_000_000) throw new IOException("备份过大");
                out.write(buffer, 0, read);
              }
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(dest.getPath(), opts);
            if (opts.outWidth < 1
                || opts.outHeight < 1
                || opts.outWidth > 8000
                || opts.outHeight > 8000) throw new IOException("备份照片无效");
          } else throw new IOException("备份包含不支持的文件");
          zip.closeEntry();
        }
      }
      if (json == null) throw new IOException("缺少物品清单");
      Inventory incoming = decode(json);
      Map<String, String> replacements = new HashMap<>();
      for (String old : incoming.photoFiles()) {
        File src = new File(staging, old);
        if (!src.isFile()) throw new IOException("备份缺少照片");
        replacements.put(old, Inventory.id() + ".jpg");
      }
      List<File> installed = new ArrayList<>();
      try {
        for (Map.Entry<String, String> pair : replacements.entrySet()) {
          File dest = new File(photos, pair.getValue());
          Files.move(new File(staging, pair.getKey()).toPath(), dest.toPath());
          installed.add(dest);
        }
        for (Inventory.Container c : incoming.containers)
          if (c.photo != null && !c.photo.isEmpty()) c.photo = replacements.get(c.photo);
        for (Inventory.Item i : incoming.items)
          for (int k = 0; k < i.photos.size(); k++)
            i.photos.set(k, replacements.get(i.photos.get(k)));
        save(incoming);
        return incoming;
      } catch (Exception e) {
        for (File f : installed) f.delete();
        throw e;
      }
    } finally {
      File[] files = staging.listFiles();
      if (files != null) for (File f : files) f.delete();
      staging.delete();
    }
  }

  static byte[] readLimited(InputStream in, long max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    long count = 0;
    while ((n = in.read(b)) != -1) {
      count += n;
      if (count > max) throw new IOException("文件过大");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }
}
