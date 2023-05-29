package com.diggsey.dpass

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.util.Log
import android.view.View
import java.lang.reflect.Modifier

class DPassAutofillService : AutofillService() {
    private fun <T> formatInt(clazz: Class<T>, prefix: String, value: Int): String {
        val desiredModifiers = Modifier.STATIC or Modifier.FINAL
        for (field in clazz.declaredFields) {
            if ((field.modifiers and desiredModifiers) == desiredModifiers) {
                if (field.name.startsWith(prefix)) {
                    if (field.type == Int::class.java) {
                        if (field.getInt(null) == value) {
                            return field.name
                        }
                    }
                }
            }
        }
        return "${prefix}<UNKNOWN>"
    }

    private fun <T> formatIntFlags(clazz: Class<T>, prefix: String, value: Int): String {
        val desiredModifiers = Modifier.STATIC or Modifier.FINAL
        var results = ArrayList<String>()
        for (field in clazz.declaredFields) {
            if ((field.modifiers and desiredModifiers) == desiredModifiers) {
                if (field.name.startsWith(prefix)) {
                    if (field.type == Int::class.java) {
                        val flag = field.getInt(null)
                        if (flag != 0 && ((value and flag) == flag)) {
                            results.add(field.name)
                        }
                    }
                }
            }
        }
        return if (results.isEmpty()) {
            "0"
        } else {
            results.joinToString("|")
        }
    }

    private fun logViewStructure(
        indent: String,
        viewNode: AssistStructure.ViewNode,
        isLast: Boolean
    ) {
        val inputType = formatIntFlags(InputType::class.java, "TYPE_", viewNode.inputType)
        val autofillType = formatInt(View::class.java, "AUTOFILL_TYPE_", viewNode.autofillType)
        val autofillHints = viewNode.autofillHints?.joinToString(",") ?: ""
        val autofillOptions = viewNode.autofillOptions?.joinToString(",") ?: ""
        val webScheme = viewNode.webScheme
        val webDomain = viewNode.webDomain
        val indentStr = indent + if (isLast) {
            "└─"
        } else {
            "├─"
        }
        val childIndentStr = indent + if (isLast) {
            "  "
        } else {
            "│ "
        }
        Log.i(
            "Autofill",
            "${indentStr}${inputType} (${autofillType} : $autofillHints : $autofillOptions : $webScheme : ${webDomain})"
        )
        for (i in 0 until viewNode.childCount) {
            logViewStructure(childIndentStr, viewNode.getChildAt(i), i == viewNode.childCount - 1)
        }
    }

    private fun logWindowStructure(window: AssistStructure.WindowNode) {
        Log.i("Autofill", "  Window (${window.title})")
        logViewStructure("  ", window.rootViewNode, true)
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val requestFlags = formatIntFlags(FillRequest::class.java, "FLAG_", request.flags)
        val context: List<FillContext> = request.fillContexts
        val structure: AssistStructure = context[context.size - 1].structure

        Log.i("Autofill", "Request (${requestFlags})")
        for (i in 0 until structure.windowNodeCount) {
            logWindowStructure(structure.getWindowNodeAt(i))
        }
        callback.onFailure("Not yet implemented")
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onFailure("Not yet implemented")
    }
}