package com.homeinventory.app;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class InventoryTest {
  private Inventory fixture() {
    Inventory d = new Inventory();
    d.nameSpace(null, "客厅");
    d.nameSpace(null, "卧室");
    d.putContainer(new Inventory.Container("box", d.spaces.get(0).id, "藤编篮", "沙发旁", "basket", ""));
    d.putContainer(new Inventory.Container("drawer", d.spaces.get(1).id, "抽屉", "衣柜内", "tool", ""));
    d.putItem(
        new Inventory.Item(
            "item",
            "box",
            "充电线",
            "电子产品",
            "备用 Type-C",
            1,
            1,
            Arrays.asList("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg")));
    return d;
  }

  @Test
  public void renameSpacePreservesContainerAndItemReferences() {
    Inventory d = fixture();
    String sid = d.spaces.get(0).id;
    d.nameSpace(sid, "起居室");
    assertEquals("起居室", d.space(d.container("box").spaceId).name);
    assertEquals("box", d.item("item").containerId);
    d.validate();
  }

  @Test
  public void emptySpacesRemainUntilExplicitlyDeleted() {
    Inventory d = fixture();
    d.nameSpace(null, "阳台");
    String id = d.spaces.get(2).id;
    assertEquals(0, d.containerCount(id));
    d.deleteSpace(id, null);
    assertEquals(2, d.spaces.size());
    assertEquals(1, d.items.size());
  }

  @Test
  public void duplicateSpaceNamesRejectedAfterTrimming() {
    Inventory d = fixture();
    assertThrows(IllegalArgumentException.class, () -> d.nameSpace(null, " 客厅 "));
    assertEquals(2, d.spaces.size());
  }

  @Test
  public void allIsReservedAndEmptyNameRejected() {
    Inventory d = fixture();
    assertThrows(IllegalArgumentException.class, () -> d.nameSpace(null, "全部"));
    assertThrows(IllegalArgumentException.class, () -> d.nameSpace(null, "   "));
  }

  @Test
  public void deletingPopulatedSpaceTransfersContainersWithoutTouchingItems() {
    Inventory d = fixture();
    String source = d.spaces.get(0).id, target = d.spaces.get(1).id;
    d.deleteSpace(source, target);
    assertEquals(target, d.container("box").spaceId);
    assertEquals("box", d.item("item").containerId);
    assertEquals(1, d.spaces.size());
    d.validate();
  }

  @Test
  public void populatedSpaceNeedsValidDifferentDestination() {
    Inventory d = fixture();
    String source = d.spaces.get(0).id;
    assertThrows(IllegalArgumentException.class, () -> d.deleteSpace(source, null));
    assertThrows(IllegalArgumentException.class, () -> d.deleteSpace(source, source));
    assertThrows(IllegalArgumentException.class, () -> d.deleteSpace(source, "missing"));
    assertEquals(2, d.spaces.size());
    assertEquals(source, d.container("box").spaceId);
  }

  @Test
  public void populatedContainerCannotBeDeleted() {
    Inventory d = fixture();
    assertThrows(IllegalArgumentException.class, () -> d.deleteContainer("box"));
    assertEquals(1, d.contents("box").size());
  }

  @Test
  public void moveUpdatesSearchResultLocationAndAllowsOldContainerDeletion() {
    Inventory d = fixture();
    d.moveItem("item", "drawer");
    assertEquals("drawer", d.search("充电线", null).get(0).containerId);
    d.deleteContainer("box");
    assertEquals(1, d.containers.size());
    d.validate();
  }

  @Test
  public void invalidMoveLeavesOldLocation() {
    Inventory d = fixture();
    assertThrows(IllegalArgumentException.class, () -> d.moveItem("item", "missing"));
    assertEquals("box", d.item("item").containerId);
  }

  @Test
  public void searchUsesNameCategoryNotesAndFilters() {
    Inventory d = fixture();
    assertEquals(1, d.search("TYPE-c", null).size());
    assertEquals(1, d.search("电子产品", null).size());
    assertEquals(0, d.search("充电线", "工具").size());
    assertEquals(0, d.search("no match", null).size());
  }

  @Test
  public void coverIsIndependentOfItemPhotos() {
    Inventory d = fixture();
    assertEquals("", d.container("box").photo);
    d.container("box").photo = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.jpg";
    d.moveItem("item", "drawer");
    assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.jpg", d.container("box").photo);
    assertEquals(2, d.photoFiles().size());
    d.validate();
  }

  @Test
  public void photoNamesRejectPathTraversal() {
    for (String path :
        Arrays.asList(
            "../photo.jpg", "/tmp/photo.jpg", "photos/hello.jpg", "https://site/image.jpg"))
      assertThrows(IllegalArgumentException.class, () -> Inventory.checkPhoto(path, false));
  }

  @Test
  public void itemsNeedPhotosAndPositiveQuantity() {
    Inventory d = fixture();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            d.putItem(
                new Inventory.Item("x", "box", "test", "其他", "", 1, 0, Collections.emptyList())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            d.putItem(
                new Inventory.Item("x", "box", "test", "其他", "", 0, 0, d.item("item").photos)));
    assertEquals(1, d.items.size());
  }

  @Test
  public void restoreValidationRejectsDanglingReferencesAndDuplicateIds() {
    Inventory d = fixture();
    d.item("item").containerId = "missing";
    assertThrows(IllegalArgumentException.class, d::validate);
    d = fixture();
    d.items.get(0).id = "box";
    assertThrows(IllegalArgumentException.class, d::validate);
  }

  @Test
  public void unnamedItemGetsReadableFallback() {
    Inventory d = fixture();
    d.putItem(new Inventory.Item("new", "drawer", " ", "其他", "", 1, 2, d.item("item").photos));
    assertEquals("未命名物品", d.item("new").name);
  }
}
