package com.tanutus.ime.editor

import android.view.inputmethod.EditorInfo

/** What the Enter key should show and do for the field currently focused. */
data class EnterKeyBehavior(val label: String, val editorAction: Int)

/**
 * Maps [EditorInfo.imeOptions] to the Enter key's label/action, per docs/keyboard-spec.md
 * ("Enterキー: `EditorInfo.imeOptions` に応じて動作・ラベルを動的に切り替える"). Follows
 * the same convention Android's own IMEs use: an explicit action label wins if the field
 * supplies one, otherwise the masked action id picks a default label; a field with no
 * meaningful action (or one that explicitly suppresses it) just gets a newline.
 */
object EditorInfoActionMapper {
    fun mapEnterKey(editorInfo: EditorInfo?): EnterKeyBehavior {
        if (editorInfo == null) return newline()

        if (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            return newline()
        }

        val actionLabel = editorInfo.actionLabel
        if (!actionLabel.isNullOrEmpty()) {
            return EnterKeyBehavior(actionLabel.toString(), editorInfo.actionId)
        }

        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_GO -> EnterKeyBehavior("Go", action)
            EditorInfo.IME_ACTION_SEARCH -> EnterKeyBehavior("検索", action)
            EditorInfo.IME_ACTION_SEND -> EnterKeyBehavior("送信", action)
            EditorInfo.IME_ACTION_NEXT -> EnterKeyBehavior("次へ", action)
            EditorInfo.IME_ACTION_DONE -> EnterKeyBehavior("完了", action)
            EditorInfo.IME_ACTION_PREVIOUS -> EnterKeyBehavior("前へ", action)
            else -> newline()
        }
    }

    private fun newline() = EnterKeyBehavior("⏎", EditorInfo.IME_ACTION_NONE)
}
