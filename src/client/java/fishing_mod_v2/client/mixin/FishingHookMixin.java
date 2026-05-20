package fishing_mod_v2.client.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import fishing_mod_v2.client.Fishingmodv2Client;

/**
 * Inject ke FishingHook.handleEntityEvent untuk mendeteksi
 * event bite yang dikirim server ke client.
 *
 * Status byte:
 *   31 = FISH_BITE_EVENT di Minecraft 1.21+
 *    9 = FISH_BITE_EVENT di versi lama (legacy)
 */
@Mixin(FishingHook.class)
public class FishingHookMixin {

    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void onHandleEntityEvent(byte status, CallbackInfo ci) {
        if (status == 31 || status == 9) {
            Fishingmodv2Client.onServerBite();
        }
    }
}
