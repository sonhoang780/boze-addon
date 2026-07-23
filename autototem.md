# AutoTotem / MainHand — điều tra chết-còn-totem (2026-07-21, tiếp 2026-07-22)

## Sự cố

Clip `MedalTVMinecraft20260721213803453.mp4` 2:45→2:58: chết bởi magic (harming pot, Noobloventr)
còn **14 totem** trong túi. 9 pop trong 13s, chat spam `[MainHand] keep: totem -> slot 8` liên tục.
User xác nhận: **đứng yên** lúc chết, **không dùng AutoShop** (GUI shop 2:45/2:49 nguồn khác).

## Phân tích đã loại

- ~~Ping gap sau pop~~ — SAI: pop cho Absorption II (8đ) + Regen II + **hurt-immunity ~10 tick**;
  refill đứng yên ~2-3 tick « 10 tick. ThunderHack pop 100 lần không chết nhờ vậy, không cần 2 tay.
- ~~Totem offhand backup (tầng 1)~~ — bỏ, lý do trên. User từ chối đúng.
- ~~AutoShop mở GUI~~ — user không dùng; GUI chỉ khoá restock đoạn 2:45-2:50 (đã fix riêng, xem dưới).

## Nghi phạm hiện tại (chưa confirm — CẦN DATA, KHÔNG ĐOÁN TIẾP)

1. **Oscillation do retry mỗi tick**: MainHand safety-net retry mỗi tick theo state local;
   server revert/resync → mỗi resync kích thêm 1 SWAP click → totem nhảy ra-vào slot 8.
   Spam "keep" trong clip dày hơn nhịp pop ở vài đoạn = dấu hiệu. Pop rơi trúng pha "ra" → chết.
   ThunderHack tránh bằng pacing: sau mỗi swap `delay = 2 + ping/25` tick (~3-4 tick) chờ ack.
2. **Carried-slot desync**: server nghĩ selected ≠ 8 → tay server-side trống dù slot 8 đầy.
   Health-snap gửi `SetCarriedItemPacket` đua với server resync.

## Việc mai

1. **Capture data (cách 1)**: user bật MainHand Debug + Boze packetlogger, chơi tới khi thấy spam
   "keep", gửi 2 log. Timestamp `[ms-trong-phút]` khớp 2 nguồn.
   Đọc: mỗi pop → đếm `TX click` SWAP (>2 = oscillation confirmed); tìm `RX heldslot ... DESYNC`
   (= nghi phạm 2); `RX setslot id0/N -> air` = server reject SWAP.
2. **Fix theo data**:
   - Oscillation → thêm cooldown ping-scaled (2 + ping/25 tick) vào safety-net stockTotem,
     đừng bắn SWAP mới khi swap trước chưa ack/revert xong.
   - Desync → xử lý `ClientboundSetHeldSlotPacket` (sync lại client hoặc re-assert slot 8).
3. **Port `runInstant()` ThunderHack** (emergency path): slot 8 trống + hotbar còn totem slot khác
   → chỉ `SetCarriedItem` sang slot đó (0 click, 0 reject, movement-proof). Refill từ từ sau.
4. Cân nhắc predictive trigger kiểu ThunderHack: crystal spawn packet + explosion damage calc,
   obsidian place gần → swap TRƯỚC damage (nặng, làm sau cùng nếu cần).

## Đã làm hôm nay (compileJava xanh hết)

- **AutoShop click drop khi moving**: InvMovePlus coi programmatic non-THROW click là Replenish
  → drop. Fix: flag `InvMovePlus.queueProgrammatic`, AutoShop.click() set quanh handleContainerInput
  → vào defer-queue thay vì drop.
- **Matrix swap (ThunderHack)**: `InvMovePlus.offhandSwapFromHotbar(mc, hotbarIdx)` — SetCarriedItem
  + SWAP_ITEM_WITH_OFFHAND action packet + SetCarriedItem về, mirror local. 0 container click,
  movement-proof. Toggle GUI `MatrixSwap` (default on), trả false → caller fallback 3-click.
  Chỉ hotbar; MatrixPick KHÔNG port được (PickFromInventoryC2SPacket bị xoá từ 1.21.2).
- **MainHand 4 cải tiến**: `CalcAbsorption` (on), `OnFall` (on, công thức TH + gate fallDistance>3),
  `OnElytra` (off — chiếm tay rocket), `CrappleSpoof` (on, Absorption amp>2 → ưu tiên gapple).
- **MainHand restock trong mọi GUI**: `menuSlotAny()` scan containerMenu.slots match player
  Inventory → SWAP click remap id đúng menu đang mở. Pop-path + safety-net bỏ gate invOk;
  apple/restore vẫn gate (cursor click id-0 only).
- **Stop-motion pop-path (tầng 2)**: moving trên GrimStrict → không bắn instant SWAP (bị reject)
  mà `InvMovePlus.requestFreeze()` → safety-net retry land frozen tick (~1 tick).
- **Debug instrumentation MainHand** (gate Debug toggle): `state` snapshot on-change,
  `TX carried/click`, `RX heldslot` (+DESYNC marker), `RX setslot` slot8/45, `POP` snapshot.
  Timestamp ms-trong-phút.

## API notes 26.1.2 (đã verify javap jar deobf)

- `ServerboundPlayerActionPacket(Action, BlockPos, Direction)`, Action có `SWAP_ITEM_WITH_OFFHAND`.
- `Inventory.SLOT_OFFHAND` = const, `getSelectedSlot()/setSelectedSlot/setItem/getItem`.
- `ClientboundSetHeldSlotPacket.slot()`; `ClientboundContainerSetSlotPacket.getContainerId/getSlot/getItem`;
  `ServerboundContainerClickPacket.containerId()/slotNum()/buttonNum()/containerInput()`;
  `ServerboundSetCarriedItemPacket.getSlot()`.
- KHÔNG có PickFromInventory packet (chỉ PickItemFromBlock/Entity, server-side).

## ThunderHack AutoTotem — điểm đáng nhớ

Nguồn: `Pan4ur/ThunderHack-Recode` `.../combat/AutoTotem.java`.
- Refill **offhand**, không phụ thuộc selected slot → miễn nhiễm carried-desync.
- Chạy trong `EventSync` (cùng đợt flush movement packet), không phải tick thường.
- Pacing `delay = 2 + ping/25` sau mỗi swap; `delay = 20` sau runInstant.
- `stopMotion`: zero velocity trước swap. `findNearestCurrentItem()`: swap qua slot cạnh selected.
- Predictive: EntitySpawn (crystal <6m) + ExplosionUtility damage predict, BlockUpdate obsidian,
  onFall/onElytra/onCreeper/onTnt/onAnchor/onMinecartTnt.
- Modes: Default/Alternative (3-click + CloseHandledScreen), Matrix (SWAP click + F-packet),
  MatrixPick (pick packet — chết ở 26.1.2), NewVersion (SWAP click slot↔40).
