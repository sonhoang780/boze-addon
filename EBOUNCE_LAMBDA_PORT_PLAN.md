# EBounce+ vs lambda's BounceElytraFly — tổng hợp + plan port lại

Nguồn đối chiếu thật: `C:\Users\conng\Downloads\lambda-1.21.11\src\main\kotlin\com\lambda\`
- `module/modules/movement/elytrafly/modes/BounceElytraFly.kt`
- `module/modules/movement/elytrafly/ObstaclePassingMode.kt`
- `interaction/handlers/GlideHandler.kt`
- `interaction/managers/rotating/RotationManager.kt`
- `util/player/PlayerUtils.kt`

Trạng thái EBounce+ hiện tại (`src/main/java/com/example/addon/modules/EBouncePlus.java`) đã **revert về bản trước khi bắt đầu soi lambda** — chỉ giữ fix `prevGliding`/UNEXPECTED STOP (bug thật, không liên quan lambda). Mọi thử nghiệm (FakeLag Y-delta, RotationManager packet-time-only, takeoff force-flag) đã bị revert hết vì gây hỏng bounce hoàn toàn khi test thật.

---

## 1. Bảng khác biệt đầy đủ EBounce+ (hiện tại) vs lambda thật

| Phần | EBounce+ (hiện tại) | Lambda (`BounceElytraFly.kt` thật) | Trạng thái |
|---|---|---|---|
| **Jump-hold khi đang bay** | `doJump=true` mỗi tick khi `isFlyingNow` | `(player.isGliding && !interrupting && jump) \|\| jumpThisTick` trong `InputUpdate` | Khớp (thiếu `!interrupting`, gắn với ObstaclePassingMode — xem mục 3) |
| **Takeoff (rời đất)** | Giữ jump liên tục lúc `onGround()`, đợi vanilla tự set cờ, `onTickPost` mới gửi `START_FALL_FLYING` khi thật sự airborne | `GlideHandler.onGlide()` gọi TRỰC TIẾP lúc còn `onGround` nếu `canStartGliding` — ép `player.startGliding()` (set cờ thật) + gửi packet NGAY, chạy ở priority `TickEvent.Pre({-1000})` — cực sớm trong tick, trước cả vanilla tick vật lý | **Khác — đã thử port, gây hỏng hoàn toàn, đã revert.** Xem mục 4 & 5. |
| **AutoPitch / dive pitch** | Set field `xRot` thật cả tick (physics + packet đều ăn), restore lại camera qua `MixinEntity.getXRot(F)` + `MixinLocalPlayer.applyInput()` (xBob) | `rotationRequest { pitch(pitch) }.submit()` — KHÔNG đụng field thật; `RotationManager` fake ở tầng packet-out VÀ tầng movement/physics (`movementPitch`, `redirectStrafeInputs`, rotation-vector interception) | Khác kiến trúc nhưng **chức năng tương đương** (cả 2 đều: physics ăn pitch giả, camera thấy pitch thật). Đã thử đổi sang kiểu lambda (fake chỉ ở packet-time) → **physics bounce chạy theo pitch camera thật** vì lambda còn hook cả tầng movement mà mình chưa port. Đã revert về cách cũ. |
| **FakeLag / queuePackets trigger** | `mc.player.onGround()` | `player.y - startPos.y < 0.163` (không cần `onGround()` thật) | **Khác — đã thử port (startY + isInDipWindow), đã revert** cùng đợt dọn lại toàn bộ. Chưa rõ có phải nguyên nhân gây hỏng hay không (chưa test riêng lẻ). |
| **Ground-touch bridging (mask gliding qua chỗ rớt cờ)** | `isGlidingMasked()` — `prevGliding` latch, có 2 mode (`VirtualMask`/`EntityFlag`) | `isGliding()` override — `prevGliding == true && !interrupting && pauseTimer.hasSurpassed(flagPause) && !BaritoneHandler.isActive` | **Thiếu `!BaritoneHandler.isActive`** — có thể port được (đã có reflection Baritone check sẵn trong `PathFinder.java`), CHƯA làm. |
| **FlagPause (đứng yên sau khi bị flag)** | `flagPauseTicksLeft` int đếm ngược | `pauseTimer: TickTimer` + `hasSurpassed(flagPause)` | Tương đương chức năng |
| **Eligibility check** | `canGlideNow()` gộp | `canStartGliding` + `canTakeoff` (2 check tách biệt, khác phạm vi) | Tương đương chức năng, gộp lại đơn giản hơn |
| **ObstaclePassingMode / PasserSettings** | Không có | Baritone raycast phát hiện chướng ngại → tự path vòng/qua | ❌ Chủ đích không port (user xác nhận) |
| **Y Motion** | Không có | Zero-hóa velocity Y khi lướt chéo góc | ❌ Chủ đích không port (user xác nhận) |
| **`jump` toggle riêng** | Không có (luôn jump-hold khi bay, không tắt được) | Có toggle `jump` riêng, tách khỏi `takeoff` | Chủ đích bỏ — EBounce+ coi tắt jump-hold là "vanilla bounce thường", không hữu ích để test |
| **Sideways-speed guard lúc takeoff fallback** | Không có | `abs(vx*rightX + vz*rightZ) >= 0.001 → return` (tránh nhảy lệch lúc đang strafe) | Chưa port — chỉ áp dụng cho nhánh fallback của lambda (`!canStartGliding`), gắn liền với cơ chế takeoff mới chưa port ổn |
| **FakeFly (giả elytra bằng chestplate thường)** | Không có trong EBounce+ (module riêng `ControlRocket` lo việc này) | `ElytraFly.fakeFly` + `flyOrFakeFly()` | Không phải thiếu sót — addon có module khác đảm nhiệm |

---

## 2. Tổng hợp toàn bộ suy luận đã rút ra (theo thứ tự phát hiện)

1. **Port ban đầu KHÔNG phải 100%** — thiếu rõ 2 mảng chủ đích (ObstaclePassingMode, Y Motion), và **1 mảng sót ngoài ý muốn**: `!BaritoneHandler.isActive` trong `isGliding()` override — có thể port được vì addon đã có Baritone reflection (PathFinder.java), nhưng documented sai là "no equivalent".

2. **Câu hỏi "camera tự do bằng cách nào"**: lambda dùng `RotationManager` — activeRotation tách biệt hoàn toàn khỏi field `player.pitch`/`yaw` thật. Field thật CHỈ bị ghi đè khi `rotationMode == Lock`; BounceElytraFly không dùng Lock mode, nên field thật **không bao giờ bị đụng**. Camera tự do vì chưa từng bị sửa — khác hẳn cách EBounce+ (sửa field thật rồi fake ngược lại lúc đọc qua MixinEntity).

3. **Thử port RotationManager-style (fake chỉ ở packet-time, mixin `sendPosition()`)** → **THẤT BẠI THẬT SỰ khi test in-game**: set AutoPitch=90 nhưng bounce vẫn chạy theo pitch camera thật. Nguyên nhân xác định: `LivingEntity#travelFallFlying` (vật lý bounce) đọc **field pitch thật** ngay trong tick vật lý client-side để tính velocity — fake chỉ ở tầng packet không đủ, vì lambda's RotationManager còn hook CẢ tầng movement/physics (`movementPitch`, rotation-vector interception) mà mình chưa port. **Đã revert về cách cũ (mutate field thật + fake ngược lại qua MixinEntity + MixinLocalPlayer.xBob).**

4. **Đọc thêm `GlideHandler.kt`**: xác nhận toggle "Takeoff" của lambda **KHÔNG cần phím thật** — gọi thẳng `GlideHandler.onGlide()` mỗi khi `canStartGliding` true (chỉ cần: không đang bay + không leo/nước + đủ trang bị — **không yêu cầu đang ở trên không**). `onGlide()` ép `player.startGliding()` (set cờ gliding thật) + gửi `START_FALL_FLYING` NGAY LẬP TỨC, có thể lúc còn đứng trên đất, TRƯỚC khi nhảy.

5. **Thử port cơ chế takeoff-ép-cờ này** (dùng `EntityFlagAccessor` sẵn có) → **THẤT BẠI, EBounce+ hỏng hoàn toàn** (chưa rõ triệu chứng cụ thể — user báo "hỏng bét" không kèm log). Giả thuyết nguyên nhân (chưa verify được, đóng nguồn Boze):
   - Lambda ép cờ ở `TickEvent.Pre({-1000})` — priority CỰC SỚM trong hệ thống event riêng của lambda, chạy trước cả vanilla tick vật lý cho frame đó → cờ đã true từ đầu khi vanilla tick, hợp lệ cả tick.
   - `EventTick.Pre` của Boze API — **không rõ chạy trước hay sau** vanilla đã tick xong vật lý/onGround cho frame đó (không có source Boze core để verify). Nếu chạy sau/lệch pha, ép `onGround=true && isFallFlying=true` đồng thời có thể bị vanilla tự phát hiện là combo vô lý và tự sửa lại trước khi cú nhảy kịp áp dụng.
   - **Đã revert về cách cũ**: chỉ giữ jump liên tục lúc `onGround`, đợi thật sự airborne rồi mới gửi packet ở `onTickPost` (chậm hơn nhưng đã proven hoạt động).

6. **Câu hỏi "vậy dùng event bus lambda được không?"** → **Không khả thi**:
   - Lambda là 1 client hoàn chỉnh (như Boze), không phải thư viện tách rời được — event bus gắn chặt vào `SafeContext`/`Manager`/module system/config system riêng.
   - 100% Kotlin, project Java — phải thêm toolchain chỉ để chạy 1 phần nhỏ.
   - Xung đột mixin thật: lambda tự mixin `LocalPlayer`/`Entity`/`ClientPacketListener` y hệt target Boze + addon đang dùng — nhúng cả lambda vào dễ crash lúc apply mixin (2 client tranh cùng target).
   - **Giải pháp thay thế**: viết mixin RIÊNG của addon, `@Inject(method="tick", at=@At("HEAD"))` thẳng vào `LocalPlayer`/`Entity` — đảm bảo chạy TRƯỚC bất kỳ tick vật lý thật nào của vanilla cho player đó, mạnh hơn cả priority `-1000` của lambda (vì cắm thẳng vào tick thật, không qua trung gian event-bus nào). Không cần mượn code lambda.

---

## 3. Chi tiết bổ sung cần điều tra thêm (chưa làm)

- **`!BaritoneHandler.isActive` trong `isGlidingMasked()`**: nếu user chạy `PathFinder` (Baritone `#elytra`) + `EBounce+` cùng lúc, mask có thể latch "còn đang bay" ngay cả khi Baritone đang chủ động điều khiển — đá nhau với cơ chế landing-disable của PathFinder. Port bằng cách tái dùng reflection Baritone check đã có sẵn trong `PathFinder.java` (`getElytraProcess()`/`isProcessActive()` pattern, hoặc đơn giản hơn: check trực tiếp `PathFinder.INSTANCE.getState()` nếu đang chạy song song — cân nhắc lúc thực hiện).

- **`!interrupting`**: gắn liền với `ObstaclePassingMode.interrupt()` — không có ý nghĩa nếu không port ObstaclePassingMode, bỏ qua.

---

## 4. Plan cho phiên làm việc tiếp theo (KHÔNG gộp — thay từng logic một, test riêng lẻ)

**Nguyên tắc chung**: mỗi bước là 1 lần build + deploy + test in-game riêng biệt, xác nhận KHÔNG hỏng behavior hiện có trước khi làm bước tiếp theo. Nếu bước nào hỏng, revert riêng bước đó, giữ nguyên các bước trước đã xác nhận ổn.

### Bước 0 — Hạ tầng: mixin tick-HEAD riêng của addon
Viết mixin mới (ví dụ `MixinLocalPlayerEarlyTick.java`), `@Inject(method = "tick", at = @At("HEAD"))` vào `LocalPlayer` (hoặc `Entity` nếu cần chạy cho mọi entity, nhưng `LocalPlayer` đủ vì chỉ cần local player). Mục đích: có 1 điểm hook đảm bảo chạy **trước** vanilla tick vật lý thật của player, để dùng cho các bước sau thay vì `EventTick.Pre` của Boze (không rõ thứ tự thật).

Việc cần làm:
- Xác nhận `LocalPlayer` có override `tick()` riêng hay kế thừa từ `Entity`/`Player` — javap để chắc chắn target đúng class có method `tick()` thật sự chạy mỗi tick game (không phải render tick).
- Thêm 1 static callback/hook đơn giản (interface hoặc list of Runnable) để EBounce+ đăng ký logic muốn chạy ở điểm này, thay vì hard-code logic EBounce+ thẳng vào mixin (giữ mixin generic, tái dùng được).
- Test: log ra tick-count/timestamp so sánh với `EventTick.Pre` của Boze để xác nhận thứ tự thật (bước debug, không phải code production).

### Bước 1 — Chỉ thay riêng: Takeoff force-flag (dùng mixin mới từ Bước 0) — ĐÃ LÀM, THẤT BẠI, ĐÃ TÌM RA ROOT CAUSE

**Trạng thái**: implement xong (`MixinLocalPlayer.earlyTick$dispatch` + `EBouncePlus.earlyTickForceTakeoff()`, registry sống ở `util/EarlyTickHooks.java` — không được sống trong `com.example.addon.mixin`, Sponge Mixin quăng `IllegalClassLoadError` vì cả package đó bị mixins.json chiếm làm mixin-only, xem thêm ghi chú build lỗi mục 5). Test in-game: **flicker/dựng lên liên tục ngay tại thời điểm takeoff**, đã loại trừ FakeLag (tắt FakeLag vẫn flicker y vậy) và loại trừ hook-ordering (đã thử CẢ 2 kiểu: `EventTick.Pre` của Boze lúc port lambda ban đầu, VÀ mixin tick-HEAD sớm hơn ở đây — cùng 1 triệu chứng).

**Root cause xác nhận** (không phải đoán — 2 cách order khác nhau, cùng 1 triệu chứng → không phải do timing):
Ép `FALL_FLYING=true` (qua `EntityFlagAccessor.invokeSetSharedFlag`) trong khi `onGround()` **vẫn còn true** tại thời điểm gọi (cú nhảy jump-hold chưa kịp áp dụng lực đẩy lên trong tick đó — impulse jump nằm trong body thật của `tick()`, chạy SAU điểm HEAD mình hook vào). Vanilla có safety-net riêng coi combo `onGround && isFallFlying` là vô lý và tự dập cờ về false ngay trong cùng tick, trước khi impulse nhảy kịp đưa player rời mặt đất — ép/dập/ép/dập mỗi tick đọc ra đúng y hệt cái flicker quan sát được. Giả thuyết này đã được viết sẵn trong plan doc gốc (mục 5 cũ, trước khi bắt đầu port lại) — nay xác nhận đúng bằng thực nghiệm có kiểm soát (A/B: baseline không mixin vẫn OK, có mixin+force-flag thì flicker, tắt FakeLag không đổi).

**Kết luận**: đây không phải vấn đề event-ordering (2 hook khác nhau, cùng lỗi) — về bản chất, ép flag glide trong khi còn onGround là tự triệt tiêu, bất kể ép ở đâu trong tick. Muốn làm được kiểu lambda thật (force-flag trước khi rời đất) cần đồng thời tự tay cấp vận tốc Y (jump impulse) NGAY TRONG cùng lần gọi ép cờ, để khi vanilla tick tới đoạn safety-net kiểm tra thì `onGround()` đã đọc ra false rồi — CHƯA THỬ, không nằm trong scope bước 1 ban đầu, cân nhắc có đáng làm tiếp hay bỏ hẳn nhánh takeoff-force-flag, giữ nguyên cơ chế cũ (wait-for-genuine-airborne, chậm hơn nhưng đã proven ổn định).

**Đã disable**: `EarlyTickHooks.register(earlyTickForceTakeoffRef)` comment out lại trong `onEnable`/`onDisable` (EBouncePlus.java), method + `util/EarlyTickHooks.java` giữ nguyên (dead code, không xoá, phòng khi quay lại làm hướng "force-flag + tự cấp velocity Y" ở trên). Mixin Bước 0 (`earlyTick$dispatch`, dispatch rỗng) vẫn ở nguyên. Có nghi vấn "từ khi thêm mixin Bước 0, takeoff chậm hẳn so với hôm trước" — đã dựng lại đúng git HEAD gốc (stash hết thay đổi phiên này, không có mixin/step1 gì cả) để A/B, nhưng chưa có kết quả xác nhận (user chuyển hướng nghi FakeLag trước khi báo lại) — CHƯA CHỐT được mixin Bước 0 có vô hại thật 100% hay không, cần test lại nếu nghi ngờ "takeoff chậm" còn tái diễn.

- Không tiếp bước 2 bằng nhánh takeoff-force-flag này. Nếu muốn thử "force-flag + tự cấp velocity Y cùng lúc" ở phiên sau, viết thành mục riêng, KHÔNG coi là tiếp tục bước 1 cũ.

### Bước 2 — Chỉ thay riêng: FakeLag Y-delta trigger
- Đổi lại `onGround()` → `player.y - startY < 0.163` (y hệt lần trước), NHƯNG lần này làm RIÊNG, không gộp với bước 1.
- Test riêng trong hầm 1x2/2-block.
- Nếu hỏng: cô lập được chính xác đây là nguyên nhân (lần trước không rõ vì làm cùng lúc với bước 1).

### Bước 3 — Cân nhắc: RotationManager packet+physics đầy đủ (KHÔNG làm packet-time-only nữa)
- Chỉ làm nếu bước 1+2 ổn và vẫn muốn port camera-free đúng kiểu lambda.
- Cần port CẢ tầng physics hook (không chỉ packet) — nghĩa là phải tìm & hook đúng chỗ `travelFallFlying`/tương đương đọc pitch để cũng đọc giá trị fake thay vì field thật, TRONG KHI field thật vẫn giữ nguyên giá trị camera. Đây là việc LỚN, cân nhắc kỹ có đáng làm không hay giữ nguyên cách mutate-field-thật hiện tại (đã proven hoạt động, chỉ khác kiến trúc chứ không phải bug).

### Bước 4 — Port `!BaritoneHandler.isActive` vào `isGlidingMasked()`
- Việc nhỏ, độc lập, ít rủi ro — có thể làm bất cứ lúc nào, kể cả trước bước 1-3 nếu muốn khởi động nhẹ nhàng trước.

---

## 5. Ghi chú vận hành

- Mọi lần build: nhớ đóng game trước (`build/libs/boze-addon.jar` bị khoá file nếu game đang chạy — đã gặp lỗi này nhiều lần, đừng copy jar dở dang đè lên jar đang hoạt động).
- `./gradlew --stop` trước khi build lại nếu gặp `ClosedFileSystemException` (gradle daemon cache stale, không phải lỗi code).
- Copy `build/libs/boze-addon.jar` → `$APPDATA/.minecraft/mods/26.1/boze-addon.jar` sau mỗi build thành công, verify size hợp lý trước khi copy (jar đầy đủ ~108-110MB, jar lỗi/dở dang thường chỉ vài MB).
- Test in-game thật sự chạy qua `./gradlew runBoze` (dùng thư mục `run/` của project riêng, KHÔNG phải `.minecraft` thật) -- config module nằm ở `run/boze/addons/1337/config.json`, log ở `run/logs/latest.log`. Copy jar vào `$APPDATA/.minecraft/mods` chỉ cần nếu test qua launcher thật, không ảnh hưởng `runBoze`.
- Sponge Mixin 2 ràng buộc cứng gặp phải khi làm mixin mới (Bước 0):
  1. Class bên trong `@Mixin`-annotated class KHÔNG được có method non-private static -- `InvalidMixinException: contains non-private static method` khi apply. Registry/API công khai (register/unregister) phải sống ở 1 class THƯỜNG bên ngoài, mixin chỉ gọi vào nó từ 1 `@Inject` private.
  2. mixins.json có `"package": "com.example.addon.mixin"` -- SPONGE MIXIN CHIẾM TOÀN BỘ package đó làm mixin-only. Bất kỳ class thường (không phải mixin) nằm trong package này mà bị gọi trực tiếp từ code khác sẽ bị `IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly`. Class registry/hỗ trợ phải đặt ở package KHÁC (vd `com.example.addon.util`), không được đặt cùng `com.example.addon.mixin` dù không tự nó là mixin.
