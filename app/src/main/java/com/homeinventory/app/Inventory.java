package com.homeinventory.app;

import java.util.*;

/** Domain rules shared by UI, persistence and restore validation. No Android dependencies. */
public final class Inventory {
  public static final List<String> CATEGORIES =
      Arrays.asList("其他", "玩具", "衣服", "工具", "日用品", "电子产品");
  public static final List<String> COVERS =
      Arrays.asList("basket", "clothes", "toy", "tool", "blanket", "headphone");

  public static final class Space {
    public String id, name;

    public Space(String id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public static final class Container {
    public String id, spaceId, name, note, art, photo;

    public Container(
        String id, String spaceId, String name, String note, String art, String photo) {
      this.id = id;
      this.spaceId = spaceId;
      this.name = name;
      this.note = note;
      this.art = art;
      this.photo = photo;
    }
  }

  public static final class Item {
    public String id, containerId, name, category, note;
    public int quantity;
    public long created;
    public final List<String> photos = new ArrayList<>();

    public Item(
        String id,
        String containerId,
        String name,
        String category,
        String note,
        int quantity,
        long created,
        List<String> photos) {
      this.id = id;
      this.containerId = containerId;
      this.name = name;
      this.category = category;
      this.note = note;
      this.quantity = quantity;
      this.created = created;
      this.photos.addAll(photos);
    }
  }

  public final List<Space> spaces = new ArrayList<>();
  public final List<Container> containers = new ArrayList<>();
  public final List<Item> items = new ArrayList<>();

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public Space space(String id) {
    return spaces.stream()
        .filter(s -> s.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("空间不存在"));
  }

  public Container container(String id) {
    return containers.stream()
        .filter(c -> c.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("容器不存在"));
  }

  public Item item(String id) {
    return items.stream()
        .filter(i -> i.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("物品不存在"));
  }

  public static String text(String s, int max, String label) {
    s = s == null ? "" : s.trim();
    if (s.isEmpty() || s.length() > max)
      throw new IllegalArgumentException(label + "需为 1–" + max + " 个字符");
    return s;
  }

  public void nameSpace(String id, String name) {
    name = text(name, 20, "空间名称");
    if (name.equals("全部")) throw new IllegalArgumentException("「全部」是筛选入口，请换个名称");
    for (Space s : spaces)
      if (!s.id.equals(id) && s.name.equals(name)) throw new IllegalArgumentException("已有同名空间");
    if (id == null) spaces.add(new Space(id(), name));
    else space(id).name = name;
  }

  public long containerCount(String sid) {
    return containers.stream().filter(c -> c.spaceId.equals(sid)).count();
  }

  public List<Item> contents(String cid) {
    return items.stream()
        .filter(i -> i.containerId.equals(cid))
        .collect(java.util.stream.Collectors.toList());
  }

  public void deleteSpace(String sid, String target) {
    space(sid);
    if (containerCount(sid) > 0) {
      if (sid.equals(target)) throw new IllegalArgumentException("请选择另一个空间");
      space(target);
      for (Container c : containers) if (c.spaceId.equals(sid)) c.spaceId = target;
    }
    spaces.removeIf(s -> s.id.equals(sid));
  }

  public void putContainer(Container c) {
    space(c.spaceId);
    c.name = text(c.name, 24, "容器名称");
    if (c.note == null || c.note.length() > 300) throw new IllegalArgumentException("位置备注最多 300 字");
    if (!COVERS.contains(c.art)) throw new IllegalArgumentException("封面无效");
    checkPhoto(c.photo, true);
    containers.removeIf(old -> old.id.equals(c.id));
    containers.add(c);
  }

  public void deleteContainer(String cid) {
    container(cid);
    if (!contents(cid).isEmpty()) throw new IllegalArgumentException("请先移动或删除容器里的物品");
    containers.removeIf(c -> c.id.equals(cid));
  }

  public void putItem(Item i) {
    container(i.containerId);
    i.name = i.name == null || i.name.trim().isEmpty() ? "未命名物品" : text(i.name, 40, "物品名称");
    if (!CATEGORIES.contains(i.category)) throw new IllegalArgumentException("请选择有效分类");
    if (i.quantity < 1 || i.quantity > 9999) throw new IllegalArgumentException("数量需在 1–9999 之间");
    if (i.note == null || i.note.length() > 1000) throw new IllegalArgumentException("备注最多 1000 字");
    if (i.photos.isEmpty() || i.photos.size() > 12)
      throw new IllegalArgumentException("每件物品需要 1–12 张照片");
    i.photos.forEach(p -> checkPhoto(p, false));
    items.removeIf(old -> old.id.equals(i.id));
    items.add(i);
  }

  public void moveItem(String iid, String target) {
    container(target);
    item(iid).containerId = target;
  }

  public List<Item> search(String query, String category) {
    String q = query.trim().toLowerCase(Locale.ROOT);
    return items.stream()
        .filter(
            i ->
                (category == null || category.equals(i.category))
                    && (i.name + " " + i.category + " " + i.note)
                        .toLowerCase(Locale.ROOT)
                        .contains(q))
        .sorted(Comparator.comparingLong((Item i) -> i.created).reversed())
        .collect(java.util.stream.Collectors.toList());
  }

  public Set<String> photoFiles() {
    Set<String> p = new HashSet<>();
    containers.forEach(
        c -> {
          if (c.photo != null && !c.photo.isEmpty()) p.add(c.photo);
        });
    items.forEach(i -> p.addAll(i.photos));
    return p;
  }

  public static void checkPhoto(String p, boolean optional) {
    if (optional && (p == null || p.isEmpty())) return;
    if (p == null || !p.matches("[a-f0-9-]{36}\\.jpg"))
      throw new IllegalArgumentException("照片路径无效");
  }

  public void validate() {
    Set<String> ids = new HashSet<>(), names = new HashSet<>();
    for (Space s : spaces) {
      unique(ids, s.id);
      if (!text(s.name, 20, "空间名称").equals(s.name) || s.name.equals("全部") || !names.add(s.name))
        throw new IllegalArgumentException("空间名称重复或无效");
    }
    for (Container c : containers) {
      unique(ids, c.id);
      space(c.spaceId);
      text(c.name, 24, "容器名称");
      if (c.note == null || c.note.length() > 300 || !COVERS.contains(c.art))
        throw new IllegalArgumentException("容器数据无效");
      checkPhoto(c.photo, true);
    }
    for (Item i : items) {
      unique(ids, i.id);
      container(i.containerId);
      text(i.name, 40, "物品名称");
      if (i.note == null
          || i.note.length() > 1000
          || !CATEGORIES.contains(i.category)
          || i.quantity < 1
          || i.quantity > 9999
          || i.photos.isEmpty()
          || i.photos.size() > 12) throw new IllegalArgumentException("物品数据无效");
      i.photos.forEach(p -> checkPhoto(p, false));
    }
  }

  private static void unique(Set<String> ids, String id) {
    if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}") || !ids.add(id))
      throw new IllegalArgumentException("记录编号重复或无效");
  }
}
