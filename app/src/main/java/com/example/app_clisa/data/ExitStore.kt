package com.example.app_clisa.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Local demonstration records; kept separate from future production inventory. */
class ExitStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("clisa_demo_exits", Context.MODE_PRIVATE)
    private fun records() = JSONArray(preferences.getString("records", "[]"))
    fun usedCodes(): Set<String> = buildSet {
        val records = records()
        for (i in 0 until records.length()) {
            val codes = records.getJSONObject(i).getJSONArray("codes")
            for (j in 0 until codes.length()) add(codes.getString(j))
        }
    }
    fun countToday(day: LocalDate): Int {
        val records = records()
        return (0 until records.length()).count { ZonedDateTime.parse(records.getJSONObject(it).getString("timestamp")).withZoneSameInstant(ZoneId.of("America/Lima")).toLocalDate() == day }
    }
    fun save(product: Product?, codes: List<String>, quantity: Int?, receiver: Receiver?, payment: String): String? = synchronized(lock) {
        DemoInventory.validate(product, codes, quantity, receiver, payment, usedCodes())?.let { return@synchronized it }
        val entry = JSONObject().put("id", UUID.randomUUID().toString())
            .put("timestamp", ZonedDateTime.now(ZoneId.of("America/Lima")).toString())
            .put("productCode", product!!.code).put("productName", product.name)
            .put("codes", JSONArray(codes)).put("quantity", quantity)
            .put("receiver", receiver!!.name).put("store", receiver.store)
            .put("registeredBy", "Alexis (demostración)").put("payment", payment)
        if (preferences.edit().putString("records", records().put(entry).toString()).commit()) null
        else "No se pudo guardar la salida. Inténtalo nuevamente."
    }
    private companion object { val lock = Any() }
}
