package com.meowmba.shared.merch

/**
 * Merch-table catalog and inventory (TEST fork). Pure Kotlin stdlib so the identical
 * file compiles in the Android app and the shared iOS (KMP) module.
 *
 * Placeholder Meow / Lumen / Toto tees are the defaults. Staff can add, edit, or
 * remove items in the in-app menu editor; the live list is what customers see and
 * what voice shopping uses. No server required.
 */
data class MerchItem(
    val sku: String,
    val name: String,
    val shortName: String,
    val description: String,
    val priceCents: Int,
    val sizes: List<String>,
    /** Words a customer might say for this item (colour, nickname, audience). */
    val keywords: Set<String>,
    /** Why Meow would suggest it, spoken in a recommendation. */
    val pitch: String,
)

data class MerchCartLine(val sku: String, val size: String, val qty: Int)

object MerchCatalog {
    /** All tees start with this stock per size unless overridden by saved inventory. */
    const val DEFAULT_STOCK_PER_SIZE = 6

    val ADULT_SIZES = listOf("S", "M", "L", "XL", "2XL")
    val YOUTH_SIZES = listOf("YS", "YM", "YL")
    val COMMON_SIZES = ADULT_SIZES + YOUTH_SIZES

    val DEFAULT_ITEMS: List<MerchItem> = listOf(
        MerchItem(
            sku = "MEOW-TEE-BLK", name = "Meow Classic Tee (black)", shortName = "Meow Classic",
            description = "Black cotton tee with the Meow wordmark.", priceCents = 2500, sizes = ADULT_SIZES,
            keywords = setOf("classic", "meow", "black", "dark", "logo", "wordmark", "original"),
            pitch = "the Meow Classic in black. It's the crowd favourite and goes with everything",
        ),
        MerchItem(
            sku = "LUMEN-TEE-WHT", name = "Lumen Glow Tee (white)", shortName = "Lumen Glow",
            description = "White tee with the Lumen avatar face.", priceCents = 2800, sizes = ADULT_SIZES,
            keywords = setOf("lumen", "glow", "white", "light", "face", "avatar", "bright"),
            pitch = "the Lumen Glow in white. It has my face on it, so obviously it's the best one",
        ),
        MerchItem(
            sku = "TOTO-TEE-GRY", name = "Toto Robot Crew Tee (heather gray)", shortName = "Toto Crew",
            description = "Heather gray tee: 'I bought this from a robot'.", priceCents = 2500, sizes = ADULT_SIZES,
            keywords = setOf("toto", "robot", "crew", "gray", "grey", "heather", "funny", "joke"),
            pitch = "the Toto Crew tee. It says you bought it from a robot, which you're doing right now",
        ),
        MerchItem(
            sku = "MEOW-TEE-KID", name = "Meow Kids Tee (black)", shortName = "Meow Kids",
            description = "Youth-size black Meow tee.", priceCents = 1800, sizes = YOUTH_SIZES,
            keywords = setOf("kid", "kids", "child", "children", "youth", "son", "daughter", "little"),
            pitch = "the Meow Kids tee. It comes in youth sizes",
        ),
    )

    private var live: MutableList<MerchItem> = DEFAULT_ITEMS.toMutableList()

    val items: List<MerchItem> get() = live.toList()

    fun bySku(sku: String): MerchItem? = live.firstOrNull { it.sku == sku }

    fun setItems(items: List<MerchItem>) {
        val cleaned = items.map { it.normalized() }.filter { it.sku.isNotBlank() && it.name.isNotBlank() }
        live = if (cleaned.isEmpty()) DEFAULT_ITEMS.toMutableList() else cleaned.toMutableList()
    }

    fun upsert(item: MerchItem) {
        val next = item.normalized()
        if (next.sku.isBlank() || next.name.isBlank()) return
        val idx = live.indexOfFirst { it.sku == next.sku }
        if (idx >= 0) live[idx] = next else live.add(next)
    }

    fun remove(sku: String) { live.removeAll { it.sku == sku } }

    fun resetToDefaults() { live = DEFAULT_ITEMS.toMutableList() }

    fun defaultStock(): MutableMap<String, Int> {
        val m = LinkedHashMap<String, Int>()
        items.forEach { item -> item.sizes.forEach { size -> m[stockKey(item.sku, size)] = DEFAULT_STOCK_PER_SIZE } }
        return m
    }

    fun stockKey(sku: String, size: String) = "$sku|$size"

    fun newSku(hint: String): String {
        val slug = hint.uppercase().replace(Regex("[^A-Z0-9]+"), "-").trim('-').take(16).ifBlank { "ITEM" }
        var sku = "CUSTOM-$slug"
        var n = 2
        while (bySku(sku) != null) { sku = "CUSTOM-$slug-$n"; n++ }
        return sku
    }

    fun keywordsFor(name: String, shortName: String, description: String): Set<String> {
        val words = (name + " " + shortName + " " + description).lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.length >= 3 }.toMutableSet()
        words.addAll(shortName.lowercase().split(Regex("\\s+")))
        return words
    }
}

internal fun MerchItem.normalized(): MerchItem {
    val sizes = sizes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
    val short = shortName.trim().ifBlank { name.trim() }
    val keys = if (keywords.isNotEmpty()) keywords else MerchCatalog.keywordsFor(name, short, description)
    val spoken = pitch.trim().ifBlank { "the $short. It's " + Money.spoken(priceCents) }
    return copy(
        sku = sku.trim(),
        name = name.trim(),
        shortName = short,
        description = description.trim(),
        priceCents = priceCents.coerceAtLeast(0),
        sizes = if (sizes.isEmpty()) MerchCatalog.ADULT_SIZES else sizes,
        keywords = keys.map { it.lowercase().trim() }.filter { it.isNotBlank() }.toSet(),
        pitch = spoken,
    )
}

/** Money is integer cents everywhere; formatting is manual so it works on every platform. */
object Money {
    fun format(cents: Int): String {
        val neg = cents < 0
        val abs = if (neg) -cents else cents
        val s = "$" + (abs / 100) + "." + (abs % 100).toString().padStart(2, '0')
        return if (neg) "-$s" else s
    }

    /** Speakable: "$25" or "$25.50". */
    fun spoken(cents: Int): String = if (cents % 100 == 0) "$" + (cents / 100) else format(cents)

    fun parseToCents(raw: String): Int? {
        val t = raw.trim().removePrefix("$").replace(",", "").trim()
        if (t.isEmpty()) return null
        val neg = t.startsWith("-")
        val body = t.removePrefix("-")
        val parts = body.split('.', limit = 2)
        val dollars = parts[0].toIntOrNull() ?: return null
        val cents = when {
            parts.size == 1 -> 0
            parts[1].isEmpty() -> 0
            parts[1].length == 1 -> (parts[1] + "0").toIntOrNull() ?: return null
            else -> parts[1].take(2).toIntOrNull() ?: return null
        }
        val total = dollars * 100 + cents
        return if (neg) -total else total
    }
}

/** Simple file/key-value persistence hook; each platform supplies its own. */
interface MerchStorage {
    fun read(): String?
    fun write(text: String)
}

/** Inventory encoded as lines "SKU|SIZE=qty". Older files are still accepted. */
object MerchInventoryCodec {
    fun encode(stock: Map<String, Int>): String = stock.entries.joinToString("\n") { "${it.key}=${it.value}" }

    fun decode(text: String?): MutableMap<String, Int> {
        val base = MerchCatalog.defaultStock()
        if (text.isNullOrBlank() || text.lineSequence().any { it.startsWith("#MERCH_MENU_V1") || it.startsWith("ITEM\t") }) {
            // New format is handled by MerchMenuCodec; fall through only for legacy inventory files.
            if (!text.isNullOrBlank() && (text.startsWith("#MERCH_MENU_V1") || text.contains("\nITEM\t") || text.startsWith("ITEM\t"))) {
                return MerchMenuCodec.decode(text).stock
            }
        }
        if (text.isNullOrBlank()) return base
        text.lineSequence().forEach { line ->
            if (line.startsWith("ITEM") || line.startsWith("#") || line.startsWith("STOCK\t")) return@forEach
            val eq = line.lastIndexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            val qty = line.substring(eq + 1).trim().toIntOrNull() ?: return@forEach
            if (key in base) base[key] = qty.coerceAtLeast(0)
        }
        return base
    }
}

data class MerchMenuFile(val items: List<MerchItem>?, val stock: MutableMap<String, Int>)

/** Combined catalog + inventory file. Legacy "SKU|SIZE=qty" files still load. */
object MerchMenuCodec {
    private const val HEADER = "#MERCH_MENU_V1"

    fun encode(items: List<MerchItem>, stock: Map<String, Int>): String {
        val sb = StringBuilder(HEADER).append('\n')
        items.forEach { item ->
            sb.append("ITEM\t")
                .append(esc(item.sku)).append('\t')
                .append(esc(item.name)).append('\t')
                .append(esc(item.shortName)).append('\t')
                .append(esc(item.description)).append('\t')
                .append(item.priceCents).append('\t')
                .append(esc(item.sizes.joinToString(","))).append('\t')
                .append(esc(item.keywords.joinToString(","))).append('\t')
                .append(esc(item.pitch)).append('\n')
        }
        stock.forEach { (key, qty) -> sb.append("STOCK\t").append(esc(key)).append('\t').append(qty).append('\n') }
        return sb.toString()
    }

    fun decode(text: String?): MerchMenuFile {
        if (text.isNullOrBlank()) return MerchMenuFile(null, MerchCatalog.defaultStock())
        val isNew = text.startsWith(HEADER) || text.contains("\nITEM\t") || text.startsWith("ITEM\t")
        if (!isNew) return MerchMenuFile(null, MerchInventoryCodec.decode(text))
        val items = ArrayList<MerchItem>()
        val rawStock = LinkedHashMap<String, Int>()
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("ITEM\t") -> {
                    val f = splitEscaped(line.substring(5))
                    if (f.size >= 8) {
                        items.add(
                            MerchItem(
                                sku = unesc(f[0]), name = unesc(f[1]), shortName = unesc(f[2]),
                                description = unesc(f[3]), priceCents = f[4].toIntOrNull() ?: 0,
                                sizes = unesc(f[5]).split(',').map { it.trim() }.filter { it.isNotBlank() },
                                keywords = unesc(f[6]).split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                                pitch = unesc(f[7]),
                            ).normalized()
                        )
                    }
                }
                line.startsWith("STOCK\t") -> {
                    val f = splitEscaped(line.substring(6))
                    if (f.size >= 2) rawStock[unesc(f[0])] = f[1].toIntOrNull()?.coerceAtLeast(0) ?: 0
                }
            }
        }
        if (items.isNotEmpty()) MerchCatalog.setItems(items)
        val stock = MerchCatalog.defaultStock()
        rawStock.forEach { (k, v) -> if (k in stock) stock[k] = v }
        return MerchMenuFile(MerchCatalog.items, stock)
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> { append('\t'); i += 2 }
                    'n' -> { append('\n'); i += 2 }
                    '\\' -> { append('\\'); i += 2 }
                    else -> { append(s[i]); i++ }
                }
            } else { append(s[i]); i++ }
        }
    }
    private fun splitEscaped(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < line.length) {
            if (line[i] == '\\' && i + 1 < line.length) { cur.append(line[i]).append(line[i + 1]); i += 2 }
            else if (line[i] == '\t') { out.add(cur.toString()); cur.clear(); i++ }
            else { cur.append(line[i]); i++ }
        }
        out.add(cur.toString())
        return out
    }
}
