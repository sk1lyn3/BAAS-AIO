package moe.baas.aio

import android.content.Context
import org.json.JSONArray

/**
 * Shop buy lists (CommonShopList / TacticalChallengeShopList) on top of [TilePicker].
 * Config format is unchanged: a 0/1 int array, one slot per entry of static `<shop>_price_list[server]`, same order
 * (row-major like the in-game shop, 4 per row).
 */
object ShopPicker {
    private fun currency(item: JSONArray?, fallback: String) = when (item?.optString(2)) {
        "creditpoints" -> "信用点"; "pyroxene" -> "青辉石"; else -> fallback
    }
    private fun price(n: Long) = when {
        n >= 1000 -> java.math.BigDecimal(n).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString() + "k"
        else -> n.toString()
    }

    fun items(goods: JSONArray, count: Int) = (0 until count).map { i ->
        val item = goods.optJSONArray(i)
        TileItem(item?.optString(0) ?: "商品 ${i + 1}",
            "#${i + 1} · " + (item?.let { price(it.optLong(1)) + if (it.optString(2) == "pyroxene") " 青辉石" else "" } ?: "未知"), item)
    }

    fun options(goods: JSONArray, count: Int, fallbackCurrency: String) = TilePicker.Options(summary = { sel ->
        val total = sel.sumOf { goods.optJSONArray(it)?.optLong(1) ?: 0L }
        "已选 ${sel.size} / $count · 刷新一次约需 ${price(total)} ${currency(goods.optJSONArray(0), fallbackCurrency)}"
    })

    fun show(context: Context, title: String, goods: JSONArray, value: JSONArray, fallbackCurrency: String, onDone: (JSONArray) -> Unit) {
        val count = maxOf(goods.length(), value.length())
        val initial = BooleanArray(count) { value.optInt(it) == 1 }
        TilePicker.show(context, title, items(goods, count), initial, options(goods, count, fallbackCurrency)) { sel ->
            onDone(JSONArray((0 until count).map { if (it in sel) 1 else 0 }))
        }
    }
}
