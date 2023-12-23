package me.hugo.thankmaslobby.player

import dev.kezz.miniphrase.MiniPhraseContext
import dev.kezz.miniphrase.audience.sendTranslated
import kotlinx.datetime.Instant
import me.hugo.thankmas.config.ConfigurationProvider
import me.hugo.thankmas.gui.Icon
import me.hugo.thankmas.gui.paginated.ConfigurablePaginatedMenu
import me.hugo.thankmas.gui.paginated.PaginatedMenu
import me.hugo.thankmas.items.hasKeyedData
import me.hugo.thankmas.items.itemsets.ItemSetRegistry
import me.hugo.thankmas.lang.TranslatedComponent
import me.hugo.thankmas.player.firstIf
import me.hugo.thankmas.player.rank.RankedPlayerData
import me.hugo.thankmas.state.StatefulValue
import me.hugo.thankmaslobby.ThankmasLobby
import me.hugo.thankmaslobby.commands.ProfileMenuAccessor
import me.hugo.thankmaslobby.database.Fishes
import me.hugo.thankmaslobby.database.PlayerData
import me.hugo.thankmaslobby.database.Rods
import me.hugo.thankmaslobby.fishing.fish.CaughtFish
import me.hugo.thankmaslobby.fishing.fish.FishType
import me.hugo.thankmaslobby.fishing.fish.FishTypeRegistry
import me.hugo.thankmaslobby.fishing.rod.FishingRod
import me.hugo.thankmaslobby.fishing.rod.FishingRodRegistry
import me.hugo.thankmaslobby.scoreboard.LobbyScoreboardManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.koin.core.component.inject
import java.util.*


public class LobbyPlayer(playerUUID: UUID, private val instance: ThankmasLobby) :
    RankedPlayerData(playerUUID, { player, locale ->
        Component.space()
            .append(Component.text("★", NamedTextColor.YELLOW))
            .append(Component.text("☆☆", NamedTextColor.GRAY))
    }),
    TranslatedComponent {

    private val configProvider: ConfigurationProvider by inject()
    private val profileMenuAccessor: ProfileMenuAccessor by inject()

    private val fishRegistry: FishTypeRegistry by inject()
    private val rodRegistry: FishingRodRegistry by inject()

    // private val unlockedNPCs: MutableList<EasterEggNPC> = mutableListOf()
    private val caughtFishes: MutableList<CaughtFish> = mutableListOf()

    /** The fishing rod this player is using to fish. */
    public lateinit var selectedRod: StatefulValue<FishingRod>
        private set

    /** List of the rods this player has unlocked. */
    public val unlockedRods: MutableMap<FishingRod, FishingRod.FishingRodData> = mutableMapOf()

    /** Menu that displays all the fishes the viewer has caught. */
    public val fishBag: PaginatedMenu =
        ConfigurablePaginatedMenu(
            configProvider.getOrLoad("menus"),
            "menus.fish-bag",
            profileMenuAccessor.fishingMenu.firstPage()
        )

    // Constructor is always run asynchronously, so we can load stuff from the database!
    init {
        val startTime = System.currentTimeMillis()
        val playerId = playerUUID.toString()

        transaction {
            val player = PlayerData.select { PlayerData.uuid eq playerId }.singleOrNull()

            val rod: FishingRod

            if (player != null) {
                rod = rodRegistry.get(player[PlayerData.selectedRod])
            } else rod = rodRegistry.getValues().first { it.tier == 1 }

            selectedRod = StatefulValue(rod).apply { subscribe { _, _, _ -> rebuildRod() } }

            // Load all the fishes this player has caught!
            Fishes.select { Fishes.whoCaught eq playerId }.forEach { result ->
                caughtFishes.add(
                    CaughtFish(
                        fishRegistry.get(result[Fishes.fishType]), playerUUID,
                        result[Fishes.pondId],
                        result[Fishes.time].toEpochMilliseconds(),
                        false
                    )
                )
            }

            // Load every rod this player has unlocked!
            Rods.select { Rods.owner eq playerId }.forEach { result ->
                unlockedRods[rodRegistry.get(result[Rods.rodId])] =
                    FishingRod.FishingRodData(result[Rods.time].toEpochMilliseconds(), false)
            }

            // If the player has no rods then we give them the default one!
            if (unlockedRods.isEmpty()) {
                unlockedRods[rodRegistry.getValues().first { it.tier == 1 }] =
                    FishingRod.FishingRodData(System.currentTimeMillis())
            }
        }

        // Add caught fishes to the fish bag menu. Max at 150 for caution!
        caughtFishes.take(150).forEach { fishBag.addIcon(Icon { player -> it.buildItem(player) }) }
        instance.logger.info("Player data for $playerUUID loaded in ${System.currentTimeMillis() - startTime}ms.")
    }

    context(MiniPhraseContext)
    public fun setTranslation(newLocale: Locale, player: Player? = null) {
        val finalPlayer = player ?: onlinePlayerOrNull ?: return

        // If we're initializing the board it's because the player just joined,
        // so we can also send them the join message!
        if (getBoardOrNull() == null) {
            initializeBoard("scoreboard.title", newLocale, player)
            finalPlayer.sendTranslated("welcome", newLocale)
        }

        val itemSetManager: ItemSetRegistry by inject()
        itemSetManager.giveSet("lobby", finalPlayer, newLocale)

        val currentBoard = lastBoardId ?: "lobby"

        val scoreboardManager: LobbyScoreboardManager by inject()
        scoreboardManager.getTemplate(currentBoard).printBoard(finalPlayer, newLocale)

        val playerManager = instance.playerManager

        Bukkit.getOnlinePlayers().forEach {
            // Update everyone's tags to the new language.
            playerManager.getPlayerDataOrNull(it.uniqueId)?.playerNameTag?.apply(finalPlayer, newLocale)
        }

        // If they are fishing also give them the new translated rod!
        rebuildRod(newLocale)
        updateHolograms(newLocale)
    }

    /** Captures [fish] on [pondId]. */
    public fun captureFish(fish: FishType, pondId: String) {
        val caughtFish = CaughtFish(fish, playerUUID, pondId)

        caughtFishes.add(caughtFish)
        fishBag.addIcon(Icon { player -> caughtFish.buildItem(player) })
    }

    /** @returns the amount of captured fishes this player has. */
    public fun fishAmount(): Int {
        return caughtFishes.size
    }

    /** @returns the amount of unique fish types this player has captured. */
    public fun uniqueFishTypes(): Int {
        return caughtFishes.groupBy { it.fishType }.size
    }

    /** Rebuilds the rod item and gives it to the player only if they already have one. */
    private fun rebuildRod(locale: Locale? = null) {
        val player = onlinePlayerOrNull ?: return

        val inventoryRod =
            player.inventory.firstIf { it.hasKeyedData(FishingRod.FISHING_ROD_ID, PersistentDataType.STRING) } ?: return

        player.inventory.setItem(inventoryRod.first, selectedRod.value.buildRod(player, locale))
    }

    public fun save(onSuccess: () -> Unit) {
        val startTime = System.currentTimeMillis()

        Bukkit.getScheduler().runTaskAsynchronously(instance, Runnable {
            val playerId = playerUUID.toString()

            transaction {
                // Update or insert this player's selected stuff!
                PlayerData.upsert {
                    it[uuid] = playerId
                    it[selectedRod] = this@LobbyPlayer.selectedRod.value.id
                    it[selectedHat] = 0
                }

                // Insert the new fishes into the database!
                Fishes.batchInsert(caughtFishes.filter { it.thisSession }) {
                    this[Fishes.whoCaught] = playerId
                    this[Fishes.fishType] = it.fishType.id
                    this[Fishes.pondId] = it.pondId
                    this[Fishes.time] = Instant.fromEpochMilliseconds(it.timeCaptured)
                }

                // Insert the new unlocked rods into the database!
                Rods.batchInsert(unlockedRods.filter { it.value.thisSession }.toList()) {
                    this[Rods.owner] = playerId
                    this[Rods.rodId] = it.first.id
                    this[Rods.time] = Instant.fromEpochMilliseconds(it.second.unlockTime)
                }
            }

            Bukkit.getScheduler().runTask(instance, Runnable {
                onSuccess()
                instance.logger.info("Player info for $playerId saved and cleaned in ${System.currentTimeMillis() - startTime}ms.")
            })
        })
    }

}