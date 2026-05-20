package fishing_mod_v2.client.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import fishing_mod_v2.client.Fishingmodv2Client;

/**
 * Mixin untuk Gui (InGameHud) untuk menangkap pesan Action Bar dari server.
 * Digunakan untuk:
 *  - Deteksi "Smart Stop" (berhasil/selesai)
 *  - Sinkronisasi jumlah shift dari server (X/Y pattern)
 *  - Deteksi kegagalan (ikan lepas)
 */
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void onSetOverlayMessage(Component component, boolean animate, CallbackInfo ci) {
        // Teruskan ke client logic untuk dianalisis
        Fishingmodv2Client.onActionBarMessage(component);
    }
}
