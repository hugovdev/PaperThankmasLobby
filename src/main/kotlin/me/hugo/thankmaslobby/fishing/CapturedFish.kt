package me.hugo.thankmaslobby.fishing

import me.hugo.thankmas.items.addLoreTranslatable
import me.hugo.thankmas.lang.Translated
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Instance of a captured fish of type [fishType] owned by [catcher].
 */
public data class CapturedFish(
    public val fishType: FishType,
    public val catcher: UUID,
    public val pondId: String,
    public val timeCaptured: Long = System.currentTimeMillis(),
) : Translated {

    /** @returns the display item of this captured fish for [viewer]. */
    public fun buildItem(viewer: Player): ItemStack = fishType.getItem(viewer.locale())
        .addLoreTranslatable("fishing.captured_fish.date", viewer.locale()) {
            parsed("date", DateTimeFormatter.ofPattern("dd/MM/yyyy").format(Date(timeCaptured).toInstant()))
        }

}