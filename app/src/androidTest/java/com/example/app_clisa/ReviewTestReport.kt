package com.example.app_clisa

import org.json.JSONObject

internal fun testReviewReport(total: Int, rows: Int, issues: Int = 0): JSONObject {
    val valid = (total - if (issues > 0) 1 else 0).coerceAtLeast(0)
    return JSONObject().put("rowsProcessed", rows).put("uniqueProducts", total).put("newProducts", valid).put("validProducts", valid).put("issueCount", issues)
        .put("quality", JSONObject().put("duplicateCodes", 0).put("duplicateSeries", 0).put("emptySeries", 0).put("negativeQuantities", 0).put("otherErrors", issues).put("negativeOnly", true))
        .put("comparison", JSONObject().put("unchanged", 0).put("increased", 0).put("decreased", 0).put("newProducts", valid).put("absent", 0).put("unknownStock", 0))
        .put("validation", JSONObject().put("validRecords", (rows - issues).coerceAtLeast(0)).put("separatedRecords", issues.coerceAtMost(rows)))
}
