package moe.nea.firmament.repo

import com.mojang.serialization.Dynamic
import io.github.moulberry.repo.IReloadable
import io.github.moulberry.repo.NEURepository
import io.github.moulberry.repo.data.NEUItem
import java.text.NumberFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.apache.logging.log4j.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.path.readText
import kotlin.jvm.optionals.getOrNull
import net.minecraft.SharedConstants
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.datafixer.Schemas
import net.minecraft.datafixer.TypeReferences
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtString
import net.minecraft.nbt.StringNbtReader
import net.minecraft.text.MutableText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import moe.nea.firmament.Firmament
import moe.nea.firmament.features.debug.ExportedTestConstantMeta
import moe.nea.firmament.repo.RepoManager.initialize
import moe.nea.firmament.util.LegacyFormattingCode
import moe.nea.firmament.util.LegacyTagParser
import moe.nea.firmament.util.MC
import moe.nea.firmament.util.MinecraftDispatcher
import moe.nea.firmament.util.SkyblockId
import moe.nea.firmament.util.TestUtil
import moe.nea.firmament.util.directLiteralStringContent
import moe.nea.firmament.util.mc.FirmamentDataComponentTypes
import moe.nea.firmament.util.mc.appendLore
import moe.nea.firmament.util.mc.displayNameAccordingToNbt
import moe.nea.firmament.util.mc.loreAccordingToNbt
import moe.nea.firmament.util.mc.modifyLore
import moe.nea.firmament.util.mc.setCustomName
import moe.nea.firmament.util.mc.setSkullOwner
import moe.nea.firmament.util.skyblockId
import moe.nea.firmament.util.transformEachRecursively

object ItemCache : IReloadable {
	private val cache: MutableMap<String, ItemStack> = ConcurrentHashMap()
	private val df = Schemas.getFixer()
	val logger = LogManager.getLogger("${Firmament.logger.name}.ItemCache")
	var isFlawless = true
		private set

	private fun NEUItem.get10809CompoundTag(): NbtCompound = NbtCompound().apply {
		put("tag", LegacyTagParser.parse(nbttag))
		putString("id", minecraftItemId)
		putByte("Count", 1)
		putShort("Damage", damage.toShort())
	}

	@ExpensiveItemCacheApi
	private fun NbtCompound.transformFrom10809ToModern() = convert189ToModern(this@transformFrom10809ToModern)
	val currentSaveVersion = SharedConstants.getGameVersion().saveVersion.id

	@ExpensiveItemCacheApi
	fun convert189ToModern(nbtComponent: NbtCompound): NbtCompound? =
		try {
			df.update(
				TypeReferences.ITEM_STACK,
				Dynamic(NbtOps.INSTANCE, nbtComponent),
				-1,
				currentSaveVersion
			).value as NbtCompound
		} catch (e: Exception) {
			isFlawless = false
			logger.error("Could not data fix up $this", e)
			null
		}

	val ItemStack.isBroken
		get() = get(FirmamentDataComponentTypes.IS_BROKEN) ?: false

	fun ItemStack.withFallback(fallback: ItemStack?): ItemStack {
		if (isBroken && fallback != null) return fallback
		return this
	}

	fun brokenItemStack(neuItem: NEUItem?, idHint: SkyblockId? = null): ItemStack {
		return ItemStack(Items.PAINTING).apply {
			setCustomName(Text.literal(neuItem?.displayName ?: idHint?.neuItem ?: "null"))
			appendLore(
				listOf(
					Text.stringifiedTranslatable(
						"firmament.repo.brokenitem",
						(neuItem?.skyblockItemId ?: idHint)
					)
				)
			)
			set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(NbtCompound().apply {
				put("ID", NbtString.of(neuItem?.skyblockItemId ?: idHint?.neuItem ?: "null"))
			}))
			set(FirmamentDataComponentTypes.IS_BROKEN, true)
		}
	}

	fun un189Lore(lore: String): MutableText {
		val base = Text.literal("")
		base.setStyle(Style.EMPTY.withItalic(false))
		var lastColorCode = Style.EMPTY
		var readOffset = 0
		while (readOffset < lore.length) {
			var nextCode = lore.indexOf('§', readOffset)
			if (nextCode < 0) {
				nextCode = lore.length
			}
			val text = lore.substring(readOffset, nextCode)
			if (text.isNotEmpty()) {
				base.append(Text.literal(text).setStyle(lastColorCode))
			}
			readOffset = nextCode + 2
			if (nextCode + 1 < lore.length) {
				val colorCode = lore[nextCode + 1]
				val formatting = LegacyFormattingCode.byCode[colorCode.lowercaseChar()] ?: LegacyFormattingCode.RESET
				val modernFormatting = formatting.modern
				if (modernFormatting.isColor) {
					lastColorCode = Style.EMPTY.withColor(modernFormatting)
				} else {
					lastColorCode = lastColorCode.withFormatting(modernFormatting)
				}
			}
		}
		return base
	}

	fun tryFindFromModernFormat(skyblockId: SkyblockId): NbtCompound? {
		val overlayFile =
			RepoManager.overlayData.getMostModernReadableOverlay(skyblockId, currentSaveVersion) ?: return null
		val overlay = StringNbtReader.readCompound(overlayFile.path.readText())
		val result = ExportedTestConstantMeta.SOURCE_CODEC.decode(
			NbtOps.INSTANCE, overlay
		).result().getOrNull() ?: return null
		val meta = result.first
		return df.update(
			TypeReferences.ITEM_STACK,
			Dynamic(NbtOps.INSTANCE, result.second),
			meta.dataVersion,
			currentSaveVersion
		).value as NbtCompound
	}

	@ExpensiveItemCacheApi
	private fun NEUItem.asItemStackNow(): ItemStack {

		try {
			var modernItemTag = tryFindFromModernFormat(this.skyblockId)
			val oldItemTag = get10809CompoundTag()
			var usedOldNbt = false
			if (modernItemTag == null) {
				usedOldNbt = true
				modernItemTag = oldItemTag.transformFrom10809ToModern()
					?: return brokenItemStack(this)
			}
			val itemInstance =
				ItemStack.fromNbt(MC.defaultRegistries, modernItemTag).getOrNull() ?: return brokenItemStack(this)
			if (usedOldNbt) {
				val tag = oldItemTag.getCompound("tag")
				val extraAttributes = tag.flatMap { it.getCompound("ExtraAttributes") }
					.getOrNull()
				if (extraAttributes != null)
					itemInstance.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(extraAttributes))
				val itemModel = tag.flatMap { it.getString("ItemModel") }.getOrNull()
				if (itemModel != null)
					itemInstance.set(DataComponentTypes.ITEM_MODEL, Identifier.of(itemModel))
			}
			itemInstance.loreAccordingToNbt = lore.map { un189Lore(it) }
			itemInstance.displayNameAccordingToNbt = un189Lore(displayName)
			return itemInstance
		} catch (e: Exception) {
			e.printStackTrace()
			return brokenItemStack(this)
		}
	}

	fun hasCacheFor(skyblockId: SkyblockId): Boolean {
		return skyblockId.neuItem in cache
	}

	@ExpensiveItemCacheApi
	fun NEUItem?.asItemStack(idHint: SkyblockId? = null, loreReplacements: Map<String, String>? = null): ItemStack {
		if (this == null) return brokenItemStack(null, idHint)
		var s = cache[this.skyblockItemId]
		if (s == null) {
			s = asItemStackNow()
			cache[this.skyblockItemId] = s
		}
		if (!loreReplacements.isNullOrEmpty()) {
			s = s.copy()!!
			s.applyLoreReplacements(loreReplacements)
			s.setCustomName(s.name.applyLoreReplacements(loreReplacements))
		}
		return s
	}

	fun ItemStack.applyLoreReplacements(loreReplacements: Map<String, String>) {
		modifyLore { lore ->
			lore.map {
				it.applyLoreReplacements(loreReplacements)
			}
		}
	}

	fun Text.applyLoreReplacements(loreReplacements: Map<String, String>): Text {
		return this.transformEachRecursively {
			var string = it.directLiteralStringContent ?: return@transformEachRecursively it
			loreReplacements.forEach { (find, replace) ->
				string = string.replace("{$find}", replace)
			}
			Text.literal(string).setStyle(it.style)
		}
	}

	var itemRecacheScope: CoroutineScope? = null

	private var recacheSoonSubmitted = mutableSetOf<SkyblockId>()

	@OptIn(ExpensiveItemCacheApi::class)
	fun recacheSoon(neuItem: NEUItem) {
		itemRecacheScope?.launch {
			if (!withContext(MinecraftDispatcher) {
					recacheSoonSubmitted.add(neuItem.skyblockId)
				}) {
				return@launch
			}
			neuItem.asItemStack()
		}
	}

	@OptIn(ExpensiveItemCacheApi::class)
	override fun reload(repository: NEURepository) {
		val j = itemRecacheScope
		j?.cancel("New reload invoked")
		cache.clear()
		isFlawless = true
		if (TestUtil.isInTest) return
		val newScope =
			CoroutineScope(
				Firmament.coroutineScope.coroutineContext +
					SupervisorJob(Firmament.globalJob) +
					Dispatchers.Default.limitedParallelism(
						(Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
					)
			)
		val items = repository.items?.items
		newScope.launch {
			val items = items ?: return@launch
			items.values.chunked(500).map { chunk ->
				async {
					chunk.forEach {
						it.asItemStack() // Rebuild cache
					}
				}
			}.awaitAll()
		}
		itemRecacheScope = newScope
	}

	fun coinItem(coinAmount: Int): ItemStack {
		var uuid = UUID.fromString("2070f6cb-f5db-367a-acd0-64d39a7e5d1b")
		var texture =
			"http://textures.minecraft.net/texture/538071721cc5b4cd406ce431a13f86083a8973e1064d2f8897869930ee6e5237"
		if (coinAmount >= 100000) {
			uuid = UUID.fromString("94fa2455-2881-31fe-bb4e-e3e24d58dbe3")
			texture =
				"http://textures.minecraft.net/texture/c9b77999fed3a2758bfeaf0793e52283817bea64044bf43ef29433f954bb52f6"
		}
		if (coinAmount >= 10000000) {
			uuid = UUID.fromString("0af8df1f-098c-3b72-ac6b-65d65fd0b668")
			texture =
				"http://textures.minecraft.net/texture/7b951fed6a7b2cbc2036916dec7a46c4a56481564d14f945b6ebc03382766d3b"
		}
		val itemStack = ItemStack(Items.PLAYER_HEAD)
		itemStack.setCustomName(Text.literal("§r§6" + NumberFormat.getInstance().format(coinAmount) + " Coins"))
		itemStack.setSkullOwner(uuid, texture)
		return itemStack
	}

	init {
		if (TestUtil.isInTest) {
			initialize()
		}
	}

}


operator fun NbtCompound.set(key: String, value: String) {
	putString(key, value)
}

operator fun NbtCompound.set(key: String, value: NbtElement) {
	put(key, value)
}
