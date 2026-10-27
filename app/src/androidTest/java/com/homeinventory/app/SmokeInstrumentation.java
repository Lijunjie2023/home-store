package com.homeinventory.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Device-level integration checks. Run on an emulator / test device, not personal data. */
public class SmokeInstrumentation extends Instrumentation {
  private int passed;
  private final StringBuilder log = new StringBuilder();
  private DataStore store;

  interface Check {
    void run() throws Exception;
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      store =
          new DataStore(
              new ContextWrapper(getTargetContext()) {
                @Override
                public File getFilesDir() {
                  File dir = new File(super.getFilesDir(), "integration-tests");
                  dir.mkdirs();
                  return dir;
                }

                @Override
                public File getCacheDir() {
                  File dir = new File(super.getCacheDir(), "integration-tests");
                  dir.mkdirs();
                  return dir;
                }
              });
      check(
          "Atomic persistence survives reopening",
          () -> {
            Inventory d = fixture();
            store.save(d);
            Inventory read = new DataStore(store.context).load();
            require(
                read.items.size() == 1 && read.items.get(0).name.equals("测试物品"),
                "Persisted item missing");
          });
      check(
          "Full backup restores photos and references",
          () -> {
            Inventory d = fixture();
            store.save(d);
            File zip = new File(store.context.getCacheDir(), "valid.zip");
            store.exportZip(Uri.fromFile(zip), d);
            Inventory restored = store.restoreZip(Uri.fromFile(zip));
            require(restored.items.size() == 1, "Item count changed");
            require(restored.photoFiles().size() == 1, "Shared photo not remapped consistently");
            require(
                !restored.items.get(0).photos.get(0).equals(d.items.get(0).photos.get(0)),
                "Restore should use fresh photo names");
            for (String path : restored.photoFiles())
              require(new File(store.photos, path).length() > 0, "Missing restored photo");
            require(store.load().containers.get(0).name.equals("测试容器"), "Restore not persisted");
          });
      check(
          "Path traversal rejected without changing data",
          () -> {
            Inventory original = fixture();
            store.save(original);
            File bad = new File(store.context.getCacheDir(), "traversal.zip");
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(bad))) {
              z.putNextEntry(new ZipEntry("photos/../../escape.jpg"));
              z.write(new byte[] {1, 2, 3});
              z.closeEntry();
            }
            expectRestoreFailure(bad);
            require(
                store.load().containers.get(0).id.equals(original.containers.get(0).id),
                "Invalid backup changed current inventory");
          });
      check(
          "Missing backup photos rejected without changing data",
          () -> {
            Inventory original = fixture();
            store.save(original);
            File bad = new File(store.context.getCacheDir(), "missing.zip");
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(bad))) {
              z.putNextEntry(new ZipEntry("inventory.json"));
              z.write(
                  DataStore.encode(fixture()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
              z.closeEntry();
            }
            expectRestoreFailure(bad);
            require(
                store.load().items.get(0).id.equals(original.items.get(0).id),
                "Missing photos overwrote current data");
          });
      check(
          "Photo imports are independent and resized",
          () -> {
            File source = new File(store.context.getCacheDir(), "source.jpg");
            Bitmap bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xffc1b08a);
            try (OutputStream out = new FileOutputStream(source)) {
              bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            }
            bitmap.recycle();
            String imported = store.importPhoto(Uri.fromFile(source));
            source.delete();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(new File(store.photos, imported).getPath(), opts);
            require(opts.outWidth == 1600 && opts.outHeight <= 1600, "Imported image not resized");
            require(
                new File(store.photos, imported).isFile(),
                "Source deletion affected imported image");
          });
      check("Native home, container form, saved state and search", this::uiFlow);
      result.putString("stream", "\n" + log + "\nPASSED " + passed + " integration checks\n");
      result.putInt("passed", passed);
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      log.append("FAILED: ").append(android.util.Log.getStackTraceString(e));
      result.putString("stream", log.toString());
      result.putInt("passed", passed);
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private void check(String name, Check c) throws Exception {
    c.run();
    passed++;
    log.append("PASS: ").append(name).append('\n');
    Bundle status = new Bundle();
    status.putString("stream", "PASS: " + name + "\n");
    sendStatus(0, status);
  }

  private void require(boolean b, String msg) {
    if (!b) throw new AssertionError(msg);
  }

  private Inventory fixture() throws Exception {
    Inventory d = new Inventory();
    d.nameSpace(null, "客厅");
    String cid = Inventory.id(), photo = Inventory.id() + ".jpg";
    Bitmap bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xff89a182);
    try (OutputStream out = new FileOutputStream(new File(store.photos, photo))) {
      bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
    }
    bitmap.recycle();
    d.putContainer(
        new Inventory.Container(cid, d.spaces.get(0).id, "测试容器", "桌子旁", "basket", photo));
    d.putItem(
        new Inventory.Item(
            Inventory.id(),
            cid,
            "测试物品",
            "工具",
            "配件齐全",
            1,
            System.currentTimeMillis(),
            Arrays.asList(photo)));
    return d;
  }

  private void expectRestoreFailure(File zip) throws Exception {
    boolean failed = false;
    try {
      store.restoreZip(Uri.fromFile(zip));
    } catch (Exception expected) {
      failed = true;
    }
    require(failed, "Invalid backup accepted");
  }

  private MainActivity current;

  private void uiFlow() throws Exception {
    current =
        (MainActivity)
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    waitForIdleSync();
    click("管理空间");
    require(waitNode("空间管理") != null, "Space management not opened");
    click("返回");
    click("＋");
    setText("例如：床底的白色收纳箱", "验收容器");
    click("封面：工具箱");
    click("保存容器");
    require(waitNode("验收容器") != null, "Created container missing");
    ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
    runOnMainSync(current::recreate);
    MainActivity recreated = (MainActivity) waitForMonitorWithTimeout(monitor, 20000);
    removeMonitor(monitor);
    require(recreated != null, "Activity recreation did not complete");
    current = recreated;
    waitForIdleSync();
    require(waitNode("验收容器") != null, "Activity recreation lost container");
    click("查找");
    require(waitNode("想找什么？") != null, "Search page not opened");
    click("设置");
    require(waitNode("空间管理  ›") != null, "Settings not opened");
    click("容器");
  }

  private View find(View n, String text) {
    CharSequence value = n instanceof TextView ? ((TextView) n).getText() : null;
    CharSequence hint = n instanceof EditText ? ((EditText) n).getHint() : null;
    CharSequence desc = n.getContentDescription();
    if (text.contentEquals(value == null ? "" : value)
        || text.contentEquals(hint == null ? "" : hint)
        || text.contentEquals(desc == null ? "" : desc)) return n;
    if (n instanceof ViewGroup group)
      for (int k = 0; k < group.getChildCount(); k++) {
        View result = find(group.getChildAt(k), text);
        if (result != null) return result;
      }
    return null;
  }

  private View waitNode(String label) throws Exception {
    final View[] result = {null};
    for (int n = 0; n < 50; n++) {
      runOnMainSync(() -> result[0] = find(current.getWindow().getDecorView(), label));
      if (result[0] != null) return result[0];
      SystemClock.sleep(150);
    }
    throw new AssertionError("Native view not found: " + label);
  }

  private void click(String label) throws Exception {
    View node = waitNode(label);
    final boolean[] clicked = {false};
    runOnMainSync(
        () -> {
          View v = node;
          while (v != null && !v.isClickable())
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
          clicked[0] = v != null && v.performClick();
        });
    require(clicked[0], "Cannot click " + label);
    waitForIdleSync();
  }

  private void setText(String hint, String value) throws Exception {
    View node = waitNode(hint);
    require(node instanceof EditText, "Expected text editor: " + hint);
    runOnMainSync(() -> ((EditText) node).setText(value));
    waitForIdleSync();
  }
}
