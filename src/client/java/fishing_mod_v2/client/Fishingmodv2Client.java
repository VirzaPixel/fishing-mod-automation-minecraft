package fishing_mod_v2.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import org.lwjgl.glfw.GLFW;
import fishing_mod_v2.client.mixin.KeyMappingAccessor;

import java.util.Random;

/**
 * Fishing Mod V2 — Shift-Based Auto Fishing
 *
 * Mekanik fishing server:
 *  1. Player cast → bobber muncul → tunggu bite
 *  2. Ikan bite → gelembung mencapai kail
 *  3. Jeda waktu 4-5 detik
 *  4. Pemain harus shift/sneak 15-20 kali (jumlah tidak menentu)
 *  5. Ikan tertangkap → bisa cast ulang
 *
 * Simulasi shift menggunakan keyShift.setPressed() agar game
 * otomatis mengirim ServerboundPlayerInputPacket ke server,
 * sehingga server menerima PlayerToggleSneakEvent yang valid.
 */
public class Fishingmodv2Client implements ClientModInitializer {

    private enum State {
        IDLE,               // Tunggu player cast manual
        WAITING_FOR_BITE,   // Bobber di air, tunggu ikan gigit
        BITE_DETECTED,      // Bite terdeteksi, jeda 4-5 detik sebelum minigame
        MINIGAME_SHIFTING,  // Shift spam 15-20x
        RECAST_WAIT         // Jeda sebelum auto-cast ulang
    }

    // ─── Konfigurasi ────────────────────────────────────────────────────────────

    // Cooldown setelah cast — abaikan penghapusan bobber awal (false positive)
    private static final int BITE_DETECT_COOLDOWN = 40; // 2 detik

    // Jeda setelah bite terdeteksi, sebelum mulai shift (4-5 detik)
    private static final int POST_BITE_DELAY_MIN = 80;  // 4 detik (80 ticks)
    private static final int POST_BITE_DELAY_MAX = 100;  // 5 detik (100 ticks)

    // Jumlah shift untuk minigame (15-20, random setiap sesi)
    private static final int SHIFT_COUNT_MIN = 15;
    private static final int SHIFT_COUNT_MAX = 20;

    // Durasi tekan shift (dalam tick)
    private static final int SHIFT_PRESS_TICKS_MIN = 1;  // 50ms
    private static final int SHIFT_PRESS_TICKS_MAX = 2;  // 100ms

    // Durasi lepas shift (dalam tick)
    private static final int SHIFT_RELEASE_TICKS_MIN = 1; // 50ms
    private static final int SHIFT_RELEASE_TICKS_MAX = 2; // 100ms

    // Jeda sebelum auto-recast (1-2 detik cooldown server)
    private static final int RECAST_WAIT_TICKS_MIN = 30; // 1.5 detik
    private static final int RECAST_WAIT_TICKS_MAX = 50; // 2.5 detik

    // ─────────────────────────────────────────────────────────────────────────────

    private static Fishingmodv2Client instance;
    private static KeyMapping toggleKey;

    private boolean enabled = false;
    private boolean biteFlag = false;
    private boolean smartStop = false;
    private boolean isShiftPressed = false;
    private State currentState = State.IDLE;

    private int tickDelay = 0;
    private int biteDetectCooldown = 0;
    private int shiftsRemaining = 0;
    private int shiftCooldown = 0;
    private int totalShiftsThisRound = 0;
    private int minigameTimeout = 0;
    private InteractionHand lastRodHand = InteractionHand.MAIN_HAND;

    private final Random random = new Random();

    public Fishingmodv2Client() {
        instance = this;
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.fishingmodv2.toggle",
            GLFW.GLFW_KEY_K,
            "category.fishingmodv2"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // ============================================================
    // Dipanggil oleh GuiMixin saat ada Action Bar / Overlay Message
    // ============================================================
    public static void onActionBarMessage(Component component) {
        if (instance == null || !instance.enabled) return;
        // Hanya proses saat sedang shifting atau bite detected
        if (instance.currentState != State.MINIGAME_SHIFTING
                && instance.currentState != State.BITE_DETECTED) return;

        String text = component.getString();
        if (text.contains("[FishingMod]")) return; // Abaikan pesan kita sendiri

        String cleanText = text.replaceAll("§[0-9a-fk-or]", ""); // Hilangkan color codes

        // 1. Deteksi STOP signal (Berhasil/Selesai)
        String lower = cleanText.toLowerCase();
        if (lower.contains("berhasil") ||
            lower.contains("tertangkap") ||
            lower.contains("caught") ||
            lower.contains("success") ||
            lower.contains("selesai")) {
            instance.smartStop = true;
            return;
        }

        // 2. Deteksi GAGAL signal
        if (lower.contains("gagal") ||
            lower.contains("lepas") ||
            lower.contains("failed") ||
            lower.contains("escaped")) {
            // Ikan lepas, reset dan tunggu cast baru
            instance.forceRelaseShift();
            instance.resetState();
            return;
        }

        // 3. Deteksi UI Minigame (Catch & Tension)
        if (lower.contains("catch") && lower.contains("tension")) {
            // Reset timeout karena UI masih aktif
            instance.minigameTimeout = 20; // 1 detik toleransi

            if (instance.currentState == State.BITE_DETECTED) {
                // Langsung mulai shifting tanpa nunggu delay habis
                instance.currentState = State.MINIGAME_SHIFTING;
                instance.shiftsRemaining = 999; // Spam terus sampai sukses/gagal
                instance.totalShiftsThisRound = 0;
                instance.shiftCooldown = 0;
            }
        }

        // 4. Deteksi jumlah shift yang dibutuhkan (Smart Detection lama)
        // Pola umum: "5/18" atau "Shift: 5/18"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
        java.util.regex.Matcher matcher = pattern.matcher(cleanText);
        if (matcher.find()) {
            try {
                int current = Integer.parseInt(matcher.group(1));
                int total = Integer.parseInt(matcher.group(2));

                if (total > 0 && total != instance.totalShiftsThisRound) {
                    instance.totalShiftsThisRound = total;
                    instance.shiftsRemaining = Math.max(0, total - current);
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    // ============================================================
    // Dipanggil oleh FishingHookMixin (entity event bite dari server)
    // ============================================================
    public static void onServerBite() {
        if (instance != null && instance.enabled) {
            instance.biteFlag = true;
        }
    }

    // ============================================================
    // Main tick — State Machine
    // ============================================================
    private void onTick(Minecraft client) {
        if (client.player == null || client.gameMode == null) return;

        // Toggle key (K)
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            forceRelaseShift();
            resetState();
            String msg = enabled
                ? "§aEnabled §7(Lempar pancingan untuk mulai)"
                : "§cDisabled";
            client.player.displayClientMessage(
                Component.literal("§6[FishingMod] " + msg), false
            );
        }

        if (!enabled) return;

        // Cek rod di tangan
        InteractionHand rodHand = getRodHand(client);
        if (rodHand != null) {
            lastRodHand = rodHand;
        } else {
            // Selama minigame atau recast, jangan interrupt
            if (currentState == State.MINIGAME_SHIFTING
                    || currentState == State.RECAST_WAIT
                    || currentState == State.BITE_DETECTED) {
                rodHand = lastRodHand;
            } else if (currentState != State.IDLE) {
                forceRelaseShift();
                resetState();
                return;
            } else {
                return;
            }
        }

        FishingHook bobber = client.player.fishing;

        switch (currentState) {

            // ── IDLE: Tunggu player cast manual ──────────────────
            case IDLE:
                if (bobber != null && !bobber.isRemoved()) {
                    currentState = State.WAITING_FOR_BITE;
                    biteDetectCooldown = BITE_DETECT_COOLDOWN;
                    biteFlag = false;
                    client.player.displayClientMessage(
                        Component.literal("§e[FishingMod] Bobber dilempar, menunggu ikan..."), false
                    );
                }
                break;

            // ── WAITING_FOR_BITE ──────────────────────────────────
            case WAITING_FOR_BITE:
                // Prioritas: cek bite flag dari entity event
                if (biteFlag) {
                    handleBiteDetected(client);
                    break;
                }

                if (biteDetectCooldown > 0) {
                    biteDetectCooldown--;

                    // Jika bobber hilang selama cooldown = player cancel manual
                    if (bobber == null || bobber.isRemoved()) {
                        currentState = State.IDLE;
                        client.player.displayClientMessage(
                            Component.literal("§7[FishingMod] Cast dibatalkan. Lempar ulang."), false
                        );
                    }
                    break;
                }

                // Setelah cooldown: bobber hilang = kemungkinan bite
                if (bobber == null || bobber.isRemoved()) {
                    handleBiteDetected(client);
                    break;
                }

                // Backup: deteksi dari gerakan bobber (dips ke bawah = bite)
                double motionY = bobber.getDeltaMovement().y;
                if (motionY < -0.12) {
                    handleBiteDetected(client);
                }
                break;

            // ── BITE_DETECTED: Jeda 4-5 detik setelah bite ──────
            case BITE_DETECTED:
                if (tickDelay > 0) {
                    tickDelay--;
                    break;
                }

                // Delay selesai → mulai shift minigame
                totalShiftsThisRound = SHIFT_COUNT_MIN
                    + random.nextInt(SHIFT_COUNT_MAX - SHIFT_COUNT_MIN + 1);
                shiftsRemaining = totalShiftsThisRound;
                shiftCooldown = 0;
                isShiftPressed = false;
                smartStop = false;
                currentState = State.MINIGAME_SHIFTING;

                client.player.displayClientMessage(
                    Component.literal("§b[FishingMod] Mulai shift! Target: "
                        + totalShiftsThisRound + "x"), false
                );
                break;

            // ── MINIGAME_SHIFTING: Toggle shift 15-20x ──────────
            case MINIGAME_SHIFTING:
                // Kurangi timeout minigame
                if (minigameTimeout > 0) {
                    minigameTimeout--;
                } else if (totalShiftsThisRound == 0) {
                    // Timeout habis (UI minigame hilang) dan ini mode tension -> Anggap selesai
                    smartStop = true;
                }

                if (shiftsRemaining <= 0 || smartStop) {
                    // Selesai shifting
                    forceRelaseShift();
                    currentState = State.RECAST_WAIT;
                    tickDelay = RECAST_WAIT_TICKS_MIN + random.nextInt(RECAST_WAIT_TICKS_MAX - RECAST_WAIT_TICKS_MIN + 1);
                    String status = smartStop ? "§aBerhasil!" : "§7Selesai.";
                    client.player.displayClientMessage(
                        Component.literal("§a[FishingMod] " + status + " Cast ulang dalam "
                            + String.format("%.1f", tickDelay / 20.0) + "s..."), false
                    );
                    break;
                }

                if (shiftCooldown > 0) {
                    shiftCooldown--;
                    break;
                }

                if (isShiftPressed) {
                    // Sudah ditekan → sekarang lepas
                    ((KeyMappingAccessor) client.options.keyShift).setIsDown(false);
                    isShiftPressed = false;
                    shiftCooldown = SHIFT_RELEASE_TICKS_MIN
                        + random.nextInt(SHIFT_RELEASE_TICKS_MAX - SHIFT_RELEASE_TICKS_MIN + 1);
                } else {
                    // Belum ditekan → tekan sekarang
                    ((KeyMappingAccessor) client.options.keyShift).setIsDown(true);
                    isShiftPressed = true;
                    shiftsRemaining--;
                    shiftCooldown = SHIFT_PRESS_TICKS_MIN
                        + random.nextInt(SHIFT_PRESS_TICKS_MAX - SHIFT_PRESS_TICKS_MIN + 1);

                    // Jangan tampilkan pesan di action bar agar tidak menimpa UI tension dari server!
                }
                break;

            // ── RECAST_WAIT: Jeda → auto-cast ulang ──────────────
            case RECAST_WAIT:
                if (tickDelay > 0) {
                    tickDelay--;
                    break;
                }

                // Pastikan tidak ada bobber aktif sebelum cast
                if (bobber != null && !bobber.isRemoved()) {
                    // Masih ada bobber, tarik dulu (right-click untuk retract)
                    client.gameMode.useItem(client.player, rodHand);
                    tickDelay = 20; // Tunggu 1 detik, lalu cast
                    break;
                }

                // Cast!
                client.gameMode.useItem(client.player, rodHand);
                currentState = State.WAITING_FOR_BITE;
                biteDetectCooldown = BITE_DETECT_COOLDOWN;
                biteFlag = false;
                client.player.displayClientMessage(
                    Component.literal("§e[FishingMod] Auto-cast! Menunggu ikan..."), false
                );
                break;
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Dipanggil saat bite terdeteksi → transisi ke BITE_DETECTED
     * dengan delay random 4-5 detik.
     */
    private void handleBiteDetected(Minecraft client) {
        biteFlag = false;
        smartStop = false;
        isShiftPressed = false;

        // AUTO RIGHT CLICK UNTUK HOOK IKAN
        InteractionHand rodHand = getRodHand(client);
        if (rodHand != null) {
            client.gameMode.useItem(client.player, rodHand);
        }

        // Delay random 4-5 detik sebelum mulai minigame shift (atau sampai UI Catch & Tension muncul)
        tickDelay = POST_BITE_DELAY_MIN
            + random.nextInt(POST_BITE_DELAY_MAX - POST_BITE_DELAY_MIN + 1);
        currentState = State.BITE_DETECTED;

        if (client.player != null) {
            client.player.displayClientMessage(
                Component.literal("§b[FishingMod] Ikan bite! Auto right-click dikirim. Menunggu minigame..."), false
            );
        }
    }

    /**
     * Pastikan shift dilepas — PENTING saat reset/disable
     * agar player tidak terjebak dalam posisi sneak.
     */
    private void forceRelaseShift() {
        Minecraft client = Minecraft.getInstance();
        if (client.options != null) {
            ((KeyMappingAccessor) client.options.keyShift).setIsDown(false);
        }
        isShiftPressed = false;
    }

    /**
     * Reset semua state ke IDLE.
     */
    private void resetState() {
        currentState = State.IDLE;
        tickDelay = 0;
        biteDetectCooldown = 0;
        shiftsRemaining = 0;
        shiftCooldown = 0;
        minigameTimeout = 0;
        biteFlag = false;
        smartStop = false;
        isShiftPressed = false;
    }

    /**
     * Cek tangan mana yang memegang fishing rod.
     */
    private InteractionHand getRodHand(Minecraft client) {
        if (client.player.getMainHandItem().getItem() instanceof FishingRodItem)
            return InteractionHand.MAIN_HAND;
        if (client.player.getOffhandItem().getItem() instanceof FishingRodItem)
            return InteractionHand.OFF_HAND;
        return null;
    }
}