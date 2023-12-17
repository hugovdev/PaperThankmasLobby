package me.hugo.thankmaslobby.fishing

import me.hugo.thankmas.items.TranslatableItem
import me.hugo.thankmas.items.addLoreTranslatable
import me.hugo.thankmas.items.nameTranslatable
import me.hugo.thankmas.lang.Translated
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import java.util.*

public class FishType(private val name: String, public val rarity: FishRarity, private val item: TranslatableItem) :
    Translated {

    /** Returns the translated fish name in [locale]. */
    public fun getFishName(locale: Locale): Component {
        return miniPhrase.translate(name, locale)
    }

    /** @returns the cached item of this fish in [locale]. */
    public fun getItem(locale: Locale): ItemStack {
        val fishItem = ItemStack(item.getBaseItem())
            .nameTranslatable(item.name, locale)
            .addLoreTranslatable(rarity.translationKey, locale)
            .addLoreTranslatable(item.lore, locale)

        return fishItem
    }

}