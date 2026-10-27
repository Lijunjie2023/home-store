package com.homeinventory.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.util.LruCache;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.json.*;

/** Native Android application; no WebView, network permission or external SDKs. */
public class MainActivity extends Activity {
  private static final int BG = 0xfff8f9f4,
      INK = 0xff26372f,
      GREEN = 0xff355440,
      MUTED = 0xff7c8b79,
      SOFT = 0xffebeee5,
      LINE = 0xffdfe6d7;
  private static final int PICK_ITEM = 41, PICK_COVER = 42, EXPORT = 43, RESTORE = 44;
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static final ExecutorService IMAGES = Executors.newFixedThreadPool(2);
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final LruCache<String, Bitmap> CACHE =
      new LruCache<>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
          return b.getAllocationByteCount();
        }
      };
  private DataStore store;
  private Inventory inv;
  private LinearLayout root, body;
  private String page = "home",
      selected = "",
      spaceFilter = "",
      search = "",
      category = "",
      itemOrigin = "container";
  private Draft draft;
  private Work work;
  private ProgressDialog progress;
  private LinearLayout coverOptions;
  private ArtView coverPreview;
  private LinearLayout results;
  private boolean loadFailed;

  static final class Draft {
    String kind = "",
        id = "",
        cid = "",
        name = "",
        space = "",
        note = "",
        cat = "其他",
        quantity = "1",
        art = "basket",
        cover = "";
    boolean merge;
    final ArrayList<String> photos = new ArrayList<>();

    String json() throws JSONException {
      return new JSONObject()
          .put("kind", kind)
          .put("id", id)
          .put("cid", cid)
          .put("name", name)
          .put("space", space)
          .put("note", note)
          .put("cat", cat)
          .put("quantity", quantity)
          .put("art", art)
          .put("cover", cover)
          .put("merge", merge)
          .put("photos", new JSONArray(photos))
          .toString();
    }

    static Draft read(String text) throws JSONException {
      JSONObject o = new JSONObject(text);
      Draft d = new Draft();
      d.kind = o.getString("kind");
      d.id = o.getString("id");
      d.cid = o.getString("cid");
      d.name = o.getString("name");
      d.space = o.getString("space");
      d.note = o.getString("note");
      d.cat = o.getString("cat");
      d.quantity = o.getString("quantity");
      d.art = o.getString("art");
      d.cover = o.getString("cover");
      d.merge = o.getBoolean("merge");
      JSONArray a = o.getJSONArray("photos");
      for (int i = 0; i < a.length(); i++) d.photos.add(a.getString(i));
      return d;
    }
  }

  static final class Work {
    MainActivity owner;
    String kind;
    List<String> photos = new ArrayList<>();
    Inventory restored;
    Exception error;
    boolean done;

    Work(String kind) {
      this.kind = kind;
    }

    void finish() {
      MAIN.post(
          () -> {
            done = true;
            if (owner != null) owner.finishWork(this);
          });
    }
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new DataStore(getApplicationContext());
    Window w = getWindow();
    w.getDecorView();
    w.setStatusBarColor(BG);
    w.setNavigationBarColor(BG);
    w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    if (Build.VERSION.SDK_INT >= 30) {
      w.setDecorFitsSystemWindows(false);
      w.getInsetsController()
          .setSystemBarsAppearance(
              WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                  | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
              WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                  | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    } else
      w.getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                  | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    try {
      inv = store.load();
    } catch (Exception e) {
      loadFailed = true;
      inv = new Inventory();
    }
    if (state != null) {
      page = state.getString("page", "home");
      selected = state.getString("selected", "");
      spaceFilter = state.getString("filter", "");
      search = state.getString("search", "");
      category = state.getString("category", "");
      itemOrigin = state.getString("origin", "container");
      try {
        if (state.containsKey("draft")) draft = Draft.read(state.getString("draft"));
      } catch (Exception ignored) {
        page = "home";
      }
    }
    Object retained = getLastNonConfigurationInstance();
    if (retained instanceof Work) {
      work = (Work) retained;
      work.owner = this;
    }
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
    render();
    if (work != null) {
      if (work.done) finishWork(work);
      else showProgress();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("page", page);
    out.putString("selected", selected);
    out.putString("filter", spaceFilter);
    out.putString("search", search);
    out.putString("category", category);
    out.putString("origin", itemOrigin);
    try {
      if (draft != null) out.putString("draft", draft.json());
    } catch (JSONException ignored) {
    }
    super.onSaveInstanceState(out);
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return work;
  }

  @Override
  protected void onDestroy() {
    if (work != null && work.owner == this) work.owner = null;
    if (progress != null) progress.dismiss();
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    goBack();
  }

  private void goBack() {
    if (work != null) return;
    if (draft != null) {
      confirm(
          "放弃本次编辑？",
          "尚未保存的内容将不会加入收纳记录。",
          "放弃编辑",
          () -> {
            String dest =
                draft.id.isEmpty()
                    ? (draft.kind.equals("item") ? "container" : "home")
                    : (draft.kind.equals("item") ? "item" : "container");
            selected =
                draft.kind.equals("item") ? (draft.id.isEmpty() ? draft.cid : draft.id) : draft.id;
            draft = null;
            prunePhotos();
            page = dest;
            render();
          });
      return;
    }
    switch (page) {
      case "container":
      case "spaces":
      case "search":
      case "settings":
        nav("home");
        break;
      case "item":
        Inventory.Item i = inv.item(selected);
        if (itemOrigin.equals("search")) nav("search");
        else openContainer(i.containerId);
        break;
      default:
        super.onBackPressed();
    }
  }

  private void nav(String dest) {
    if (draft != null) return;
    hideKeyboard();
    page = dest;
    render();
  }

  private void openContainer(String id) {
    hideKeyboard();
    selected = id;
    page = "container";
    render();
  }

  private void openItem(String id, String from) {
    selected = id;
    itemOrigin = from;
    page = "item";
    render();
  }

  private int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private GradientDrawable outline(int color) {
    GradientDrawable d = shape(color, 14);
    d.setStroke(dp(1), LINE);
    return d;
  }

  private TextView text(String value, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    t.setLineSpacing(dp(3), 1);
    if (bold) t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  private void gap(LinearLayout l, int h) {
    View v = new View(this);
    l.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
  }

  private void pad(View v, int n) {
    v.setPadding(dp(n), dp(n), dp(n), dp(n));
  }

  private TextView action(String label, Runnable run) {
    TextView t = text(label, 14, GREEN, true);
    t.setGravity(Gravity.CENTER);
    t.setMinHeight(dp(46));
    t.setPadding(dp(12), dp(10), dp(12), dp(10));
    t.setBackground(shape(SOFT, 13));
    t.setOnClickListener(v -> run.run());
    t.setFocusable(true);
    t.setContentDescription(label);
    return t;
  }

  private TextView primary(String label, Runnable run) {
    TextView t = action(label, run);
    t.setBackground(shape(GREEN, 15));
    t.setTextColor(0xffffffff);
    t.setMinHeight(dp(52));
    return t;
  }

  private void hint(LinearLayout l, String value) {
    TextView t = text(value, 12, MUTED, false);
    t.setLineSpacing(dp(5), 1);
    l.addView(t);
  }

  private LinearLayout paddedScroll(LinearLayout parent) {
    ScrollView s = new ScrollView(this);
    s.setFillViewport(true);
    s.setClipToPadding(false);
    LinearLayout l = column();
    l.setPadding(dp(21), dp(20), dp(21), dp(26));
    s.addView(l);
    parent.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
    return l;
  }

  private void top(String title, Runnable back, String extra, Runnable click) {
    LinearLayout bar = row();
    bar.setPadding(dp(9), dp(6), dp(12), dp(6));
    TextView b = action("‹", back);
    b.setTextSize(29);
    b.setBackgroundColor(Color.TRANSPARENT);
    b.setContentDescription("返回");
    bar.addView(b, new LinearLayout.LayoutParams(dp(48), dp(48)));
    TextView t = text(title, 18, INK, true);
    t.setMaxLines(1);
    t.setEllipsize(TextUtils.TruncateAt.END);
    bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    if (extra != null) bar.addView(action(extra, click));
    body.addView(bar);
  }

  private void render() {
    hideKeyboard();
    root = column();
    root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i =
                insets.getInsets(
                    WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
          } else
            v.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    body = column();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    root.requestApplyInsets();
    if (loadFailed) {
      LinearLayout l = paddedScroll(body);
      l.addView(text("暂时无法读取收纳记录", 24, INK, true));
      gap(l, 16);
      hint(l, "为保护已有数据，应用没有覆盖原记录。你可以尝试重新打开应用，或恢复以前导出的备份。");
      gap(l, 24);
      l.addView(primary("从备份恢复", this::requestRestore));
      return;
    }
    try {
      switch (page) {
        case "container":
          containerScreen();
          break;
        case "item":
          itemScreen();
          break;
        case "search":
          searchScreen();
          break;
        case "settings":
          settingsScreen();
          break;
        case "spaces":
          spacesScreen();
          break;
        case "containerForm":
          containerFormScreen();
          break;
        case "itemForm":
          itemFormScreen();
          break;
        default:
          page = "home";
          homeScreen();
      }
      if (draft == null) bottomNav();
    } catch (IllegalArgumentException e) {
      page = "home";
      draft = null;
      selected = "";
      body.removeAllViews();
      homeScreen();
      bottomNav();
    }
  }

  private void bottomNav() {
    LinearLayout bar = row();
    bar.setPadding(dp(16), dp(5), dp(16), dp(4));
    bar.setBackgroundColor(0xfffbfcf7);
    String current =
        page.equals("search")
            ? "search"
            : page.equals("settings") || page.equals("spaces") ? "settings" : "home";
    String[] pages = {"home", "search", "settings"},
        labels = {"容器", "查找", "设置"},
        icons = {"▦", "⌕", "☷"};
    for (int n = 0; n < 3; n++) {
      String dest = pages[n];
      LinearLayout cell = column();
      cell.setGravity(Gravity.CENTER);
      cell.setMinimumHeight(dp(62));
      TextView icon = text(icons[n], 25, current.equals(dest) ? GREEN : MUTED, false);
      icon.setGravity(Gravity.CENTER);
      cell.addView(icon);
      gap(cell, 3);
      TextView label =
          text(labels[n], 11, current.equals(dest) ? GREEN : MUTED, current.equals(dest));
      label.setGravity(Gravity.CENTER);
      cell.addView(label);
      cell.setContentDescription(labels[n]);
      cell.setFocusable(true);
      cell.setOnClickListener(v -> nav(dest));
      bar.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }
    View divider = new View(this);
    divider.setBackgroundColor(LINE);
    root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    root.addView(bar);
  }

  private void homeScreen() {
    LinearLayout fixed = column();
    fixed.setPadding(dp(21), dp(14), dp(21), dp(13));
    TextView searchBox = action("⌕   找找东西放在哪儿", () -> nav("search"));
    searchBox.setGravity(Gravity.CENTER_VERTICAL);
    searchBox.setTextColor(MUTED);
    searchBox.setTypeface(null, Typeface.NORMAL);
    fixed.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(50)));
    gap(fixed, 14);
    LinearLayout tabs = row();
    HorizontalScrollView sc = new HorizontalScrollView(this);
    sc.setHorizontalScrollBarEnabled(false);
    LinearLayout chips = row();
    chips.addView(
        chip(
            "全部",
            spaceFilter.isEmpty(),
            () -> {
              spaceFilter = "";
              render();
            }));
    for (Inventory.Space s : inv.spaces)
      chips.addView(
          chip(
              s.name,
              spaceFilter.equals(s.id),
              () -> {
                spaceFilter = s.id;
                render();
              }));
    sc.addView(chips);
    scrollToSelected(sc, chips);
    tabs.addView(sc, new LinearLayout.LayoutParams(0, -2, 1));
    TextView manage = action("管理空间", () -> nav("spaces"));
    manage.setTextSize(12);
    manage.setBackground(outline(BG));
    tabs.addView(manage);
    fixed.addView(tabs);
    body.addView(fixed);
    View line = new View(this);
    line.setBackgroundColor(LINE);
    body.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    LinearLayout content = paddedScroll(body);
    List<Inventory.Container> list =
        inv.containers.stream()
            .filter(c -> spaceFilter.isEmpty() || c.spaceId.equals(spaceFilter))
            .collect(java.util.stream.Collectors.toList());
    LinearLayout heading = row();
    heading.addView(
        text("我的容器  " + list.size(), 20, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(primary("＋", () -> editContainer(null)));
    content.addView(heading);
    gap(content, 18);
    if (list.isEmpty()) {
      empty(content, "给物品一个家", "先创建一个容器，再从相册添加物品。", "＋ 创建容器", () -> editContainer(null));
    } else grid(content, list, c -> containerCard(c));
  }

  private void scrollToSelected(HorizontalScrollView scroll, LinearLayout chips) {
    scroll.post(
        () -> {
          for (int n = 0; n < chips.getChildCount(); n++) {
            View v = chips.getChildAt(n);
            if (v.isSelected()) {
              scroll.scrollTo(Math.max(0, v.getLeft() - dp(45)), 0);
              break;
            }
          }
        });
  }

  private TextView chip(String label, boolean on, Runnable click) {
    TextView c = action(label, click);
    c.setTextSize(13);
    c.setSelected(on);
    c.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
    c.setBackground(shape(on ? GREEN : BG, 22));
    c.setTextColor(on ? Color.WHITE : MUTED);
    return c;
  }

  private void empty(LinearLayout l, String title, String subtitle, String button, Runnable click) {
    gap(l, 38);
    TextView icon = text("⌂", 44, 0xff9baa91, false);
    icon.setGravity(Gravity.CENTER);
    l.addView(icon, new LinearLayout.LayoutParams(-1, dp(80)));
    TextView t = text(title, 20, INK, true);
    t.setGravity(Gravity.CENTER);
    l.addView(t);
    gap(l, 12);
    TextView h = text(subtitle, 13, MUTED, false);
    h.setGravity(Gravity.CENTER);
    l.addView(h);
    if (button != null) {
      gap(l, 24);
      l.addView(primary(button, click));
    }
  }

  private <T> void grid(LinearLayout parent, List<T> values, Function<T, View> card) {
    int cols = getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
    for (int n = 0; n < values.size(); n += cols) {
      LinearLayout r = row();
      r.setGravity(Gravity.TOP);
      for (int k = 0; k < cols; k++) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (k > 0) lp.leftMargin = dp(12);
        if (n + k < values.size()) r.addView(card.apply(values.get(n + k)), lp);
        else r.addView(new View(this), lp);
      }
      parent.addView(r);
      gap(parent, 20);
    }
  }

  private View containerCard(Inventory.Container c) {
    LinearLayout card = column();
    FrameLayout image = new FrameLayout(this);
    int width =
        (getResources().getConfiguration().screenWidthDp - 54)
            / (getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2);
    image.addView(new ArtView(c.art, c.photo), new FrameLayout.LayoutParams(-1, -1));
    TextView badge = text(inv.contents(c.id).size() + " 件物品", 11, INK, false);
    pad(badge, 6);
    badge.setBackground(shape(0xeeffffff, 8));
    FrameLayout.LayoutParams bp =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
    bp.setMargins(dp(10), 0, 0, dp(10));
    image.addView(badge, bp);
    card.addView(image, new LinearLayout.LayoutParams(-1, dp(Math.max(135, width))));
    gap(card, 10);
    TextView title = text(c.name, 15, INK, true);
    title.setMaxLines(2);
    card.addView(title);
    gap(card, 5);
    card.addView(text(inv.space(c.spaceId).name, 12, MUTED, false));
    card.setOnClickListener(v -> openContainer(c.id));
    card.setFocusable(true);
    card.setContentDescription(
        c.name + "，" + inv.space(c.spaceId).name + "，" + inv.contents(c.id).size() + " 件物品");
    return card;
  }

  private View itemCard(Inventory.Item i) {
    LinearLayout card = column();
    card.addView(
        new ArtView("basket", i.photos.get(0)), new LinearLayout.LayoutParams(-1, dp(155)));
    gap(card, 10);
    card.addView(text(i.name, 15, INK, true));
    gap(card, 5);
    card.addView(text(i.category + " · " + i.quantity + " 件", 12, MUTED, false));
    card.setOnClickListener(v -> openItem(i.id, "container"));
    card.setFocusable(true);
    card.setContentDescription(i.name);
    return card;
  }

  private void containerScreen() {
    Inventory.Container c = inv.container(selected);
    top("容器相册", () -> nav("home"), "编辑", () -> editContainer(c));
    LinearLayout l = paddedScroll(body);
    hint(l, inv.space(c.spaceId).name + " / 容器");
    gap(l, 8);
    l.addView(text(c.name, 28, INK, true));
    gap(l, 8);
    hint(l, "⌖ " + (c.note.isEmpty() ? "尚未填写具体位置" : c.note));
    gap(l, 20);
    l.addView(new ArtView(c.art, c.photo), new LinearLayout.LayoutParams(-1, dp(200)));
    gap(l, 24);
    LinearLayout h = row();
    h.addView(
        text("收纳在这里  " + inv.contents(c.id).size(), 18, INK, true),
        new LinearLayout.LayoutParams(0, -2, 1));
    h.addView(action("＋ 添加物品", () -> editItem(null, c.id)));
    l.addView(h);
    gap(l, 16);
    List<Inventory.Item> items = inv.contents(c.id);
    if (items.isEmpty()) empty(l, "这里还空着", "从相册选张照片，记录第一件物品吧。", null, null);
    else grid(l, items, this::itemCard);
    gap(l, 16);
    l.addView(primary("＋ 从相册添加物品", () -> editItem(null, c.id)));
  }

  private void itemScreen() {
    Inventory.Item i = inv.item(selected);
    Inventory.Container c = inv.container(i.containerId);
    top("物品详情", this::goBack, "编辑", () -> editItem(i, c.id));
    LinearLayout l = paddedScroll(body);
    l.addView(text(i.name, 28, INK, true));
    gap(l, 10);
    hint(l, i.category + "  ·  " + i.quantity + " 件");
    gap(l, 20);
    l.addView(new ArtView("basket", i.photos.get(0)), new LinearLayout.LayoutParams(-1, dp(270)));
    if (i.photos.size() > 1) {
      gap(l, 14);
      for (int k = 1; k < i.photos.size(); k++) {
        l.addView(
            new ArtView("basket", i.photos.get(k)), new LinearLayout.LayoutParams(-1, dp(180)));
        gap(l, 12);
      }
    }
    gap(l, 20);
    LinearLayout position = info(l, "存放位置");
    TextView path =
        action(
            "⌖ " + inv.space(c.spaceId).name + " → " + c.name + "  ›", () -> openContainer(c.id));
    path.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    position.addView(path);
    if (!c.note.isEmpty()) hint(position, c.note);
    LinearLayout notes = info(l, "物品备注");
    notes.addView(text(i.note.isEmpty() ? "还没有备注" : i.note, 14, INK, false));
    gap(l, 10);
    l.addView(primary("移动到其他容器", () -> moveDialog(i)));
    gap(l, 10);
    hint(l, "移动后，搜索结果会显示新的存放位置。");
  }

  private LinearLayout info(LinearLayout parent, String title) {
    LinearLayout l = column();
    pad(l, 16);
    l.setBackground(shape(Color.WHITE, 17));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(12);
    parent.addView(l, lp);
    hint(l, title);
    gap(l, 9);
    return l;
  }

  private void moveDialog(Inventory.Item item) {
    List<Inventory.Container> options =
        inv.containers.stream()
            .filter(c -> !c.id.equals(item.containerId))
            .collect(java.util.stream.Collectors.toList());
    if (options.isEmpty()) {
      message("还没有其他容器", "请先创建另一个容器，再移动物品。");
      return;
    }
    String[] names =
        options.stream()
            .map(c -> inv.space(c.spaceId).name + " → " + c.name)
            .toArray(String[]::new);
    new AlertDialog.Builder(this)
        .setTitle("移动到哪里？")
        .setItems(
            names,
            (d, n) -> {
              if (commit(() -> inv.moveItem(item.id, options.get(n).id))) {
                render();
                toast("物品位置已更新");
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void searchScreen() {
    LinearLayout header = column();
    header.setPadding(dp(21), dp(20), dp(21), dp(10));
    header.addView(text("想找什么？", 28, INK, true));
    gap(header, 8);
    hint(header, "搜名称、分类或备注，找到它的位置。");
    gap(header, 18);
    EditText field =
        edit(
            "例如：电钻、衣服、充电器",
            search,
            40,
            false,
            v -> {
              search = v;
              updateResults();
            });
    field.setSingleLine(true);
    field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    header.addView(field);
    gap(header, 12);
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout chips = row();
    chips.addView(
        chip(
            "全部",
            category.isEmpty(),
            () -> {
              category = "";
              render();
            }));
    for (String cat : Inventory.CATEGORIES)
      chips.addView(
          chip(
              cat,
              category.equals(cat),
              () -> {
                category = cat;
                render();
              }));
    hs.addView(chips);
    scrollToSelected(hs, chips);
    header.addView(hs);
    body.addView(header);
    results = paddedScroll(body);
    updateResults();
  }

  private void updateResults() {
    if (results == null || !page.equals("search")) return;
    results.removeAllViews();
    List<Inventory.Item> found = inv.search(search, category.isEmpty() ? null : category);
    results.addView(
        text(
            (search.trim().isEmpty() ? "全部物品" : "搜索结果") + "  " + found.size() + " 件",
            18,
            INK,
            true));
    gap(results, 18);
    for (Inventory.Item i : found) {
      Inventory.Container c = inv.container(i.containerId);
      results.addView(
          listRow(
              i.name,
              "⌖ "
                  + inv.space(c.spaceId).name
                  + " → "
                  + c.name
                  + "\n"
                  + i.category
                  + " · "
                  + i.quantity
                  + " 件",
              new ArtView("basket", i.photos.get(0)),
              () -> openItem(i.id, "search")));
      gap(results, 12);
    }
    if (found.isEmpty())
      empty(results, "没有找到物品", search.isEmpty() ? "添加物品后，就能在这里查找。" : "试试其他名称或分类。", null, null);
  }

  private View listRow(String title, String sub, View image, Runnable click) {
    LinearLayout r = row();
    pad(r, 11);
    r.setBackground(shape(Color.WHITE, 16));
    if (image != null) {
      r.addView(image, new LinearLayout.LayoutParams(dp(72), dp(76)));
      View gap = new View(this);
      r.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
    }
    LinearLayout labels = column();
    labels.addView(text(title, 15, INK, true));
    gap(labels, 7);
    hint(labels, sub);
    r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    if (click != null) {
      r.setOnClickListener(v -> click.run());
      r.setFocusable(true);
      r.setContentDescription(title + "，" + sub);
    }
    return r;
  }

  private void settingsScreen() {
    top("收纳小管家", () -> nav("home"), null, null);
    LinearLayout l = paddedScroll(body);
    l.addView(text("家里有数", 28, INK, true));
    gap(l, 10);
    hint(l, "你的记录，留在自己的设备里。");
    gap(l, 28);
    LinearLayout stats = info(l, "我的收纳");
    stats.addView(
        text(inv.containers.size() + " 个容器   ·   " + inv.items.size() + " 件物品", 18, INK, true));
    gap(l, 12);
    l.addView(action("空间管理  ›", () -> nav("spaces")));
    gap(l, 12);
    l.addView(action("导出完整备份  ↓", this::requestExport));
    gap(l, 12);
    l.addView(action("从备份恢复  ↑", this::requestRestore));
    gap(l, 24);
    hint(
        l,
        "备份包含物品信息和照片。卸载应用或清除应用数据会删除本机记录，建议定期备份到自己保存的位置。\n\n"
            + "照片通过系统相册选择器导入，删除本 App 内的记录不会删除相册原图。\n\n"
            + "版本 0.1.0 · 本地离线使用 · 无账号与云同步");
  }

  private void spacesScreen() {
    top("空间管理", () -> nav("home"), "＋ 新增", () -> spaceNameDialog(null));
    LinearLayout l = paddedScroll(body);
    hint(l, "按家里的实际布局，安排每一个空间。");
    gap(l, 20);
    for (Inventory.Space s : inv.spaces) {
      LinearLayout box = column();
      pad(box, 15);
      box.setBackground(shape(Color.WHITE, 16));
      box.addView(text(s.name, 18, INK, true));
      gap(box, 6);
      hint(box, inv.containerCount(s.id) + " 个容器");
      gap(box, 12);
      LinearLayout actions = row();
      actions.addView(
          action("修改", () -> spaceNameDialog(s)), new LinearLayout.LayoutParams(0, -2, 1));
      TextView del = action("删除", () -> deleteSpaceDialog(s));
      del.setTextColor(0xff9c6655);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      lp.leftMargin = dp(10);
      actions.addView(del, lp);
      box.addView(actions);
      l.addView(box);
      gap(l, 14);
    }
    if (inv.spaces.isEmpty()) empty(l, "还没有空间", "先添加客厅、卧室或其他空间吧。", null, null);
    gap(l, 12);
    l.addView(primary("＋ 新增空间", () -> spaceNameDialog(null)));
  }

  private void spaceNameDialog(Inventory.Space space) {
    EditText e = edit("例如：厨房", space == null ? "" : space.name, 20, false, v -> {});
    LinearLayout box = column();
    pad(box, 22);
    box.addView(e);
    gap(box, 12);
    hint(box, space == null ? "名称不可重复，「全部」是筛选入口。" : "修改后，容器和物品的存放路径会同步更新。");
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(space == null ? "新增空间" : "修改空间")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    b -> {
                      if (commit(
                          () ->
                              inv.nameSpace(
                                  space == null ? null : space.id, e.getText().toString()))) {
                        dialog.dismiss();
                        render();
                        toast("空间已保存");
                      }
                    }));
    dialog.show();
  }

  private void deleteSpaceDialog(Inventory.Space s) {
    long count = inv.containerCount(s.id);
    if (count == 0) {
      confirm(
          "删除「" + s.name + "」？",
          "这是一个空空间，删除不会影响其他记录。",
          "确认删除",
          () -> {
            if (commit(() -> inv.deleteSpace(s.id, null))) {
              if (spaceFilter.equals(s.id)) spaceFilter = "";
              render();
            }
          });
      return;
    }
    List<Inventory.Space> targets =
        inv.spaces.stream()
            .filter(t -> !t.id.equals(s.id))
            .collect(java.util.stream.Collectors.toList());
    if (targets.isEmpty()) {
      message("先新增另一个空间", "这里还有 " + count + " 个容器。新增空间后，才能转移容器并删除此空间。");
      return;
    }
    String[] names = targets.stream().map(t -> t.name).toArray(String[]::new);
    final int[] choice = {-1};
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("将 " + count + " 个容器迁往哪里？")
            .setSingleChoiceItems(names, -1, (d, n) -> choice[0] = n)
            .setNegativeButton("取消", null)
            .setPositiveButton("转移并删除", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    b -> {
                      if (choice[0] < 0) {
                        toast("请先选择目标空间");
                        return;
                      }
                      if (commit(() -> inv.deleteSpace(s.id, targets.get(choice[0]).id))) {
                        if (spaceFilter.equals(s.id)) spaceFilter = "";
                        dialog.dismiss();
                        render();
                        toast("容器与物品已保留，空间已删除");
                      }
                    }));
    dialog.show();
  }

  private EditText edit(
      String placeholder, String value, int limit, boolean multi, Consumer<String> change) {
    EditText e = new EditText(this);
    e.setTextSize(15);
    e.setTextColor(INK);
    e.setHintTextColor(0xff9ba694);
    e.setHint(placeholder);
    e.setText(value);
    e.setBackground(outline(Color.WHITE));
    e.setPadding(dp(13), dp(13), dp(13), dp(13));
    e.setMinHeight(dp(50));
    e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(limit)});
    if (multi) {
      e.setInputType(
          android.text.InputType.TYPE_CLASS_TEXT
              | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
      e.setMinLines(3);
      e.setGravity(Gravity.TOP);
    } else e.setSingleLine(true);
    e.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int st, int b, int c) {
            change.accept(s.toString());
          }

          public void afterTextChanged(Editable s) {}
        });
    return e;
  }

  private EditText field(
      LinearLayout parent,
      String label,
      String placeholder,
      String value,
      int max,
      boolean multi,
      Consumer<String> change) {
    parent.addView(text(label, 13, GREEN, false));
    gap(parent, 8);
    EditText e = edit(placeholder, value, max, multi, change);
    parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    gap(parent, 20);
    return e;
  }

  private Spinner select(
      LinearLayout parent, String label, List<String> names, int selected, IntConsumer action) {
    parent.addView(text(label, 13, GREEN, false));
    gap(parent, 8);
    Spinner s = new Spinner(this);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
    s.setAdapter(adapter);
    s.setSelection(Math.max(0, selected));
    s.setBackground(outline(Color.WHITE));
    s.setPadding(dp(10), dp(3), dp(8), dp(3));
    s.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
            action.accept(position);
          }

          public void onNothingSelected(AdapterView<?> p) {}
        });
    parent.addView(s, new LinearLayout.LayoutParams(-1, dp(52)));
    gap(parent, 20);
    return s;
  }

  private void editContainer(Inventory.Container c) {
    if (inv.spaces.isEmpty()) {
      message("请先创建一个空间", "在空间管理中新增客厅、卧室或其他空间，再来创建容器。");
      nav("spaces");
      return;
    }
    draft = new Draft();
    draft.kind = "container";
    if (c != null) {
      draft.id = c.id;
      draft.name = c.name;
      draft.space = c.spaceId;
      draft.note = c.note;
      draft.art = c.art;
      draft.cover = c.photo == null ? "" : c.photo;
    } else draft.space = spaceFilter.isEmpty() ? inv.spaces.get(0).id : spaceFilter;
    page = "containerForm";
    render();
  }

  private void containerFormScreen() {
    if (draft == null) {
      page = "home";
      homeScreen();
      return;
    }
    top(draft.id.isEmpty() ? "新建容器" : "编辑容器", this::goBack, null, null);
    LinearLayout l = paddedScroll(body);
    field(l, "容器名称 *", "例如：床底的白色收纳箱", draft.name, 24, false, v -> draft.name = v);
    List<String> names =
        inv.spaces.stream().map(s -> s.name).collect(java.util.stream.Collectors.toList());
    int pos = 0;
    for (int n = 0; n < inv.spaces.size(); n++)
      if (inv.spaces.get(n).id.equals(draft.space)) pos = n;
    select(l, "所在空间 *", names, pos, n -> draft.space = inv.spaces.get(n).id);
    LinearLayout summary = row();
    coverPreview = new ArtView(draft.art, draft.cover);
    summary.addView(coverPreview, new LinearLayout.LayoutParams(dp(92), dp(92)));
    LinearLayout label = column();
    label.setPadding(dp(14), 0, 0, 0);
    label.addView(text("容器封面", 15, INK, true));
    gap(label, 8);
    hint(label, "选择默认插画，或使用自己的照片。");
    summary.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    l.addView(summary);
    gap(l, 16);
    coverOptions = column();
    l.addView(coverOptions);
    refreshCoverOptions();
    gap(l, 12);
    l.addView(action("＋ 从相册选择自定义封面", () -> pickPhotos(PICK_COVER)));
    gap(l, 10);
    hint(l, "保存后同步显示在首页与容器详情。物品照片不会自动替换封面。");
    gap(l, 22);
    field(l, "具体位置备注", "再具体一点，会更容易找到", draft.note, 300, true, v -> draft.note = v);
    if (!draft.id.isEmpty()) {
      TextView del = action("删除容器", () -> confirmDeleteContainer(draft.id));
      del.setTextColor(0xff9c6655);
      l.addView(del);
    }
    formSave("保存容器", this::saveContainer);
  }

  private void refreshCoverOptions() {
    coverOptions.removeAllViews();
    String[] labels = {"藤编篮", "衣物", "玩具", "工具箱", "织物", "数码"};
    for (int n = 0; n < 6; n += 3) {
      LinearLayout r = row();
      for (int k = 0; k < 3; k++) {
        int index = n + k;
        String art = Inventory.COVERS.get(index);
        LinearLayout cell = column();
        pad(cell, 4);
        GradientDrawable background = shape(Color.WHITE, 13);
        if (draft.cover.isEmpty() && draft.art.equals(art)) background.setStroke(dp(2), GREEN);
        cell.setBackground(background);
        cell.addView(new ArtView(art, ""), new LinearLayout.LayoutParams(-1, dp(70)));
        TextView name = text(labels[index], 12, MUTED, false);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(5), 0, dp(5));
        cell.addView(name);
        cell.setContentDescription("封面：" + labels[index]);
        cell.setOnClickListener(
            v -> {
              draft.cover = "";
              draft.art = art;
              coverPreview.setImage(art, "");
              refreshCoverOptions();
            });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (k > 0) lp.leftMargin = dp(9);
        r.addView(cell, lp);
      }
      coverOptions.addView(r);
      gap(coverOptions, 9);
    }
  }

  private void saveContainer() {
    if (draft == null) return;
    Draft d = draft;
    String id = d.id.isEmpty() ? Inventory.id() : d.id;
    Inventory.Container c = new Inventory.Container(id, d.space, d.name, d.note, d.art, d.cover);
    if (commit(() -> inv.putContainer(c))) {
      draft = null;
      prunePhotos();
      openContainer(id);
      toast("容器已保存");
    }
  }

  private void confirmDeleteContainer(String id) {
    if (!inv.contents(id).isEmpty()) {
      message("容器里还有物品", "请先逐件移动或删除物品，再删除容器。");
      return;
    }
    confirm(
        "删除这个空容器？",
        "删除后无法恢复。",
        "确认删除",
        () -> {
          if (commit(() -> inv.deleteContainer(id))) {
            draft = null;
            nav("home");
            toast("容器已删除");
          }
        });
  }

  private void editItem(Inventory.Item i, String cid) {
    draft = new Draft();
    draft.kind = "item";
    draft.cid = cid;
    if (i != null) {
      draft.id = i.id;
      draft.name = i.name;
      draft.cat = i.category;
      draft.note = i.note;
      draft.quantity = String.valueOf(i.quantity);
      draft.photos.addAll(i.photos);
    }
    page = "itemForm";
    render();
  }

  private void itemFormScreen() {
    if (draft == null) {
      page = "home";
      homeScreen();
      return;
    }
    top(draft.id.isEmpty() ? "把物品记下来" : "编辑物品", this::goBack, null, null);
    LinearLayout l = paddedScroll(body);
    Inventory.Container c = inv.container(draft.cid);
    hint(l, "⌖ " + inv.space(c.spaceId).name + " → " + c.name);
    gap(l, 18);
    l.addView(
        action(draft.photos.isEmpty() ? "＋ 从相册选择照片 *" : "＋ 继续添加照片", () -> pickPhotos(PICK_ITEM)));
    gap(l, 12);
    if (!draft.photos.isEmpty()) {
      for (int n = 0; n < draft.photos.size(); n++) {
        final int position = n;
        LinearLayout r = row();
        r.addView(
            new ArtView("basket", draft.photos.get(n)),
            new LinearLayout.LayoutParams(dp(92), dp(90)));
        TextView label = text("照片 " + (n + 1), 13, MUTED, false);
        label.setPadding(dp(14), 0, 0, 0);
        r.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(
            action(
                "移除",
                () -> {
                  draft.photos.remove(position);
                  render();
                }));
        l.addView(r);
        gap(l, 10);
      }
    }
    if (draft.id.isEmpty()) {
      CheckBox merge = new CheckBox(this);
      merge.setText("将所选照片合并为一件物品");
      merge.setTextSize(13);
      merge.setTextColor(GREEN);
      merge.setChecked(draft.merge);
      merge.setOnCheckedChangeListener((v, on) -> draft.merge = on);
      l.addView(merge);
      hint(l, "多选时默认每张照片创建一件物品；合并后为同一物品的多个角度。");
    } else hint(l, "编辑时添加的照片会作为这件物品的补充照片。");
    gap(l, 22);
    field(l, "物品名称", "例如：米色针织衫（可稍后填写）", draft.name, 40, false, v -> draft.name = v);
    select(
        l,
        "分类",
        Inventory.CATEGORIES,
        Inventory.CATEGORIES.indexOf(draft.cat),
        n -> draft.cat = Inventory.CATEGORIES.get(n));
    EditText quantity = field(l, "数量 *", "1", draft.quantity, 4, false, v -> draft.quantity = v);
    quantity.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    field(l, "备注", "尺码、用途、配件……", draft.note, 1000, true, v -> draft.note = v);
    if (!draft.id.isEmpty()) {
      TextView del = action("删除物品", this::deleteItem);
      del.setTextColor(0xff9c6655);
      l.addView(del);
    }
    formSave("保存物品", this::saveItems);
  }

  private void saveItems() {
    Draft d = draft;
    int q;
    try {
      q = Integer.parseInt(d.quantity);
    } catch (Exception e) {
      toast("请填写 1–9999 之间的数量");
      return;
    }
    if (d.photos.isEmpty()) {
      toast("请至少选择一张照片");
      return;
    }
    List<Inventory.Item> records = new ArrayList<>();
    long now = System.currentTimeMillis();
    if (!d.id.isEmpty()) {
      Inventory.Item old = inv.item(d.id);
      records.add(new Inventory.Item(d.id, d.cid, d.name, d.cat, d.note, q, old.created, d.photos));
    } else if (d.merge) {
      records.add(
          new Inventory.Item(Inventory.id(), d.cid, d.name, d.cat, d.note, q, now, d.photos));
    } else {
      for (int n = 0; n < d.photos.size(); n++) {
        String name = d.name;
        if (!name.trim().isEmpty() && d.photos.size() > 1) {
          String suffix = " " + (n + 1);
          name = name.trim();
          name = name.substring(0, Math.min(name.length(), 40 - suffix.length())) + suffix;
        }
        records.add(
            new Inventory.Item(
                Inventory.id(),
                d.cid,
                name,
                d.cat,
                d.note,
                q,
                now + n,
                Arrays.asList(d.photos.get(n))));
      }
    }
    if (commit(() -> records.forEach(inv::putItem))) {
      draft = null;
      prunePhotos();
      if (d.id.isEmpty()) openContainer(d.cid);
      else openItem(d.id, itemOrigin);
      toast("物品已保存");
    }
  }

  private void deleteItem() {
    String id = draft.id, cid = draft.cid;
    confirm(
        "删除这件物品？",
        "仅删除本 App 的物品记录，不影响相册原图。",
        "确认删除",
        () -> {
          if (commit(() -> inv.items.removeIf(i -> i.id.equals(id)))) {
            draft = null;
            openContainer(cid);
            toast("物品已删除");
          }
        });
  }

  private void formSave(String label, Runnable click) {
    LinearLayout footer = column();
    footer.setPadding(dp(21), dp(10), dp(21), dp(12));
    footer.setBackgroundColor(BG);
    footer.addView(primary(label, click));
    body.addView(footer);
  }

  private void pickPhotos(int request) {
    hideKeyboard();
    if (Build.VERSION.SDK_INT >= 33) {
      Intent picker = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*");
      int remaining = request == PICK_COVER ? 1 : 12 - draft.photos.size();
      if (remaining < 1) {
        toast("最多保留 12 张照片，请先移除不需要的照片");
        return;
      }
      if (remaining > 1)
        picker.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, remaining);
      try {
        startActivityForResult(picker, request);
        return;
      } catch (ActivityNotFoundException ignored) {
      }
    }
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.setType("image/*");
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request == PICK_ITEM);
    try {
      startActivityForResult(intent, request);
    } catch (ActivityNotFoundException e) {
      message("无法打开系统相册", "请检查系统相册或文件选择器是否可用。");
    }
  }

  private void requestExport() {
    if (inv.items.isEmpty() && inv.containers.isEmpty() && inv.spaces.isEmpty()) {
      toast("暂无可备份的记录");
      return;
    }
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("application/zip")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(
                Intent.EXTRA_TITLE,
                "家里有数-"
                    + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date())
                    + ".zip");
    try {
      startActivityForResult(intent, EXPORT);
    } catch (ActivityNotFoundException e) {
      message("无法创建备份", "系统文件选择器不可用。");
    }
  }

  private void requestRestore() {
    confirm(
        "从备份恢复？",
        "恢复将替换当前容器与物品记录。建议先导出当前数据。只有完整、有效的备份才会被应用。",
        "选择备份",
        () -> {
          Intent intent =
              new Intent(Intent.ACTION_OPEN_DOCUMENT)
                  .setType("*/*")
                  .addCategory(Intent.CATEGORY_OPENABLE);
          try {
            startActivityForResult(intent, RESTORE);
          } catch (ActivityNotFoundException e) {
            message("无法选择备份", "系统文件选择器不可用。");
          }
        });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null) return;
    if (request == PICK_COVER || request == PICK_ITEM) {
      if (draft == null) return;
      List<Uri> uris = new ArrayList<>();
      ClipData clip = data.getClipData();
      if (clip != null) {
        for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
      } else if (data.getData() != null) uris.add(data.getData());
      if (uris.isEmpty()) return;
      if (request == PICK_COVER && uris.size() != 1) {
        toast("请选择一张封面照片");
        return;
      }
      if (request == PICK_ITEM && uris.size() + draft.photos.size() > 12) {
        toast("一次最多保留 12 张照片");
        return;
      }
      Work task = beginWork(request == PICK_COVER ? "cover" : "photos");
      DataStore ds = store;
      IO.execute(
          () -> {
            try {
              for (Uri uri : uris) task.photos.add(ds.importPhoto(uri));
            } catch (Exception e) {
              task.error = e;
              for (String file : task.photos) new File(ds.photos, file).delete();
              task.photos.clear();
            }
            task.finish();
          });
    } else if (request == EXPORT && data.getData() != null) {
      try {
        Inventory snapshot = DataStore.decode(DataStore.encode(inv));
        Uri uri = data.getData();
        Work task = beginWork("export");
        DataStore ds = store;
        IO.execute(
            () -> {
              try {
                ds.exportZip(uri, snapshot);
              } catch (Exception e) {
                task.error = e;
              }
              task.finish();
            });
      } catch (Exception e) {
        toast("无法准备备份");
      }
    } else if (request == RESTORE && data.getData() != null) {
      Work task = beginWork("restore");
      DataStore ds = store;
      Uri uri = data.getData();
      IO.execute(
          () -> {
            try {
              task.restored = ds.restoreZip(uri);
            } catch (Exception e) {
              task.error = e;
            }
            task.finish();
          });
    }
  }

  private Work beginWork(String kind) {
    work = new Work(kind);
    work.owner = this;
    showProgress();
    return work;
  }

  @SuppressWarnings("deprecation")
  private void showProgress() {
    if (progress != null) progress.dismiss();
    progress = new ProgressDialog(this);
    progress.setMessage(
        work.kind.equals("restore")
            ? "正在检查并恢复备份…"
            : work.kind.equals("export") ? "正在导出记录与照片…" : "正在导入照片…");
    progress.setCancelable(false);
    progress.show();
  }

  private void finishWork(Work task) {
    if (work != task) return;
    if (progress != null) {
      progress.dismiss();
      progress = null;
    }
    work = null;
    task.owner = null;
    if (task.error != null) {
      message(
          "未能完成操作",
          task.kind.equals("restore")
              ? "备份无法恢复，原有记录未更改。请确认文件是本 App 导出的完整备份。"
              : "无法读取或保存文件。请检查存储空间，或选择其他文件后重试。");
      return;
    }
    if (task.kind.equals("restore")) {
      inv = task.restored;
      loadFailed = false;
      draft = null;
      spaceFilter = "";
      page = "home";
      selected = "";
      CACHE.evictAll();
      prunePhotos();
      render();
      toast("备份已恢复");
    } else if (task.kind.equals("export")) toast("完整备份已导出");
    else if (draft != null) {
      if (task.kind.equals("cover")) draft.cover = task.photos.get(0);
      else draft.photos.addAll(task.photos);
      render();
    }
  }

  private void prunePhotos() {
    Set<String> keep = inv.photoFiles();
    if (draft != null) {
      keep.addAll(draft.photos);
      if (!draft.cover.isEmpty()) keep.add(draft.cover);
    }
    File dir = store.photos;
    IO.execute(
        () -> {
          File[] files = dir.listFiles();
          if (files != null)
            for (File file : files) if (!keep.contains(file.getName())) file.delete();
        });
  }

  private boolean commit(Runnable change) {
    String before;
    try {
      before = DataStore.encode(inv);
    } catch (Exception e) {
      toast("无法读取当前记录");
      return false;
    }
    try {
      change.run();
      store.save(inv);
      prunePhotos();
      return true;
    } catch (Exception e) {
      try {
        inv = DataStore.decode(before);
      } catch (Exception ignored) {
      }
      if (e instanceof IllegalArgumentException) toast(e.getMessage());
      else message("没有保存成功", "请检查设备存储空间。原有记录已保留。");
      return false;
    }
  }

  private void confirm(String title, String msg, String yes, Runnable run) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(msg)
        .setNegativeButton("取消", null)
        .setPositiveButton(yes, (d, n) -> run.run())
        .show();
  }

  private void message(String title, String msg) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(msg)
        .setPositiveButton("知道了", null)
        .show();
  }

  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
  }

  private void hideKeyboard() {
    View v = getCurrentFocus();
    if (v != null) {
      ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(v.getWindowToken(), 0);
      v.clearFocus();
    }
  }

  /** Rounded native illustrations and asynchronously loaded, center-cropped photos. */
  private final class ArtView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private String art, file;
    private boolean loading;
    private String failedFile = "";

    ArtView(String art, String file) {
      super(MainActivity.this);
      setImage(art, file);
      setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setImage(String art, String file) {
      this.art = art;
      this.file = file == null ? "" : file;
      this.failedFile = "";
      invalidate();
    }

    private void requestImage() {
      if (loading || file.isEmpty() || file.equals(failedFile)) return;
      loading = true;
      String key = file;
      File path = new File(store.photos, key);
      IMAGES.execute(
          () -> {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path.getPath(), opts);
            opts.inSampleSize = 1;
            while (opts.outWidth / opts.inSampleSize > 900
                || opts.outHeight / opts.inSampleSize > 900) opts.inSampleSize *= 2;
            opts.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeFile(path.getPath(), opts);
            if (bitmap != null) CACHE.put(key, bitmap);
            MAIN.post(
                () -> {
                  loading = false;
                  if (isDestroyed()) return;
                  if (bitmap == null && key.equals(file)) failedFile = key;
                  invalidate();
                });
          });
    }

    private void fill(int color) {
      paint.setColor(color);
      paint.setStyle(Paint.Style.FILL);
    }

    private void rect(float x, float y, float w, float h, int color, int radius, Canvas c) {
      fill(color);
      c.drawRoundRect(x, y, x + w, y + h, radius, radius, paint);
    }

    private void path(Canvas c, int color, float... points) {
      fill(color);
      Path p = new Path();
      p.moveTo(points[0], points[1]);
      for (int n = 2; n < points.length; n += 2) p.lineTo(points[n], points[n + 1]);
      p.close();
      c.drawPath(p, paint);
    }

    private void line(Canvas c, int color, float width, float... points) {
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(width);
      Path p = new Path();
      p.moveTo(points[0], points[1]);
      for (int n = 2; n < points.length; n += 2) p.lineTo(points[n], points[n + 1]);
      c.drawPath(p, paint);
      paint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      float w = getWidth(), h = getHeight();
      clip.reset();
      bounds.set(0, 0, w, h);
      clip.addRoundRect(bounds, dp(18), dp(18), Path.Direction.CW);
      c.save();
      c.clipPath(clip);
      Bitmap image = file.isEmpty() ? null : CACHE.get(file);
      if (image == null) requestImage();
      if (image != null) {
        float scale = Math.max(w / image.getWidth(), h / image.getHeight()),
            rw = image.getWidth() * scale,
            rh = image.getHeight() * scale;
        paint.setColor(Color.WHITE);
        bounds.set((w - rw) / 2, (h - rh) / 2, (w + rw) / 2, (h + rh) / 2);
        c.drawBitmap(image, null, bounds, paint);
        c.restore();
        return;
      }
      int bg =
          switch (art) {
            case "clothes" -> 0xffeee5d9;
            case "toy" -> 0xffe8ebd8;
            case "tool" -> 0xffdfe5db;
            case "blanket" -> 0xffe5e9db;
            case "headphone" -> 0xffe5dfd2;
            default -> 0xffe8e0cf;
          };
      c.drawColor(bg);
      float scale = Math.min(w / 200, h / 185);
      c.translate((w - 200 * scale) / 2, (h - 185 * scale) / 2);
      c.scale(scale, scale);
      fill(0x1235452b);
      c.drawOval(35, 146, 165, 164, paint);
      switch (art) {
        case "clothes":
          path(
              c,
              0xffd1b89a,
              69,
              53,
              91,
              43,
              111,
              43,
              133,
              53,
              162,
              87,
              140,
              104,
              126,
              87,
              126,
              146,
              74,
              146,
              74,
              87,
              60,
              104,
              38,
              87);
          line(c, 0xffbba081, 3, 90, 45, 100, 63, 111, 45);
          for (int x = 83; x <= 119; x += 12) line(c, 0xffbba081, 3, x, 92, x, 134);
          break;
        case "toy":
          rect(43, 107, 49, 40, 0xffc68961, 5, c);
          rect(99, 96, 51, 51, 0xffb3c19e, 5, c);
          path(c, 0xffd9bc77, 62, 104, 88, 58, 115, 104);
          fill(0xffd2a48b);
          c.drawCircle(137, 64, 21, paint);
          break;
        case "tool":
          rect(40, 89, 119, 60, 0xff6e7f6c, 9, c);
          line(c, 0xff53634f, 8, 78, 89, 78, 73, 122, 73, 122, 89);
          line(c, 0xff4d6048, 3, 40, 111, 159, 111);
          rect(92, 103, 17, 18, 0xffd3bf85, 3, c);
          path(c, 0xff9a9f8c, 67, 66, 82, 33, 92, 38, 77, 71);
          break;
        case "blanket":
          rect(45, 55, 110, 96, 0xffc2c8ae, 13, c);
          for (int y = 73; y <= 130; y += 19) line(c, 0xffe3e4ce, 3, 48, y, 152, y);
          for (int x = 68; x <= 140; x += 24) line(c, 0xffe3e4ce, 3, x, 57, x, 150);
          for (int x = 51; x < 150; x += 12) line(c, 0xffa4ae91, 3, x, 151, x, 161);
          break;
        case "headphone":
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(14);
          paint.setColor(0xfff3eee0);
          c.drawArc(63, 51, 137, 125, 180, 180, false, paint);
          line(c, 0xfff3eee0, 14, 63, 88, 63, 112);
          line(c, 0xfff3eee0, 14, 137, 88, 137, 112);
          rect(54, 99, 21, 40, 0xff89977e, 10, c);
          rect(126, 99, 21, 40, 0xff89977e, 10, c);
          break;
        default:
          paint.setColor(0xff9f794f);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(7);
          c.drawArc(70, 32, 130, 136, 180, 180, false, paint);
          path(c, 0xffece6d5, 68, 88, 77, 69, 100, 59, 124, 72, 137, 88);
          path(c, 0xffae865c, 45, 82, 157, 82, 147, 147, 55, 147);
          for (int y = 92; y <= 132; y += 13) line(c, 0xffd8ba8e, 3, 50, y, 151, y);
          for (int x = 68; x <= 128; x += 20) line(c, 0xffd8ba8e, 3, x, 85, x, 145);
          break;
      }
      if (!file.isEmpty() && file.equals(failedFile)) {
        rect(31, 155, 138, 22, 0xccffffff, 6, c);
        fill(MUTED);
        paint.setTextSize(11);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText("照片暂时无法读取", 100, 170, paint);
        paint.setTextAlign(Paint.Align.LEFT);
      }
      c.restore();
    }
  }
}
