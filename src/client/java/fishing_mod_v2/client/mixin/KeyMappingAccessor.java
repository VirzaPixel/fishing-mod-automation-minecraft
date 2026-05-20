package fishing_mod_v2.client.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin Accessor untuk mengakses field private 'isDown' di KeyMapping.
 * Digunakan untuk mensimulasikan key press/release secara programmatic.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

    @Accessor("isDown")
    void setIsDown(boolean isDown);

    @Accessor("isDown")
    boolean getIsDown();
}
