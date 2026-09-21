package tokyo.tanutus.ime.editor

import android.text.InputType
import android.view.inputmethod.EditorInfo
import tokyo.tanutus.ime.core.state.InputMode

/**
 * How much of the kana-kanji pipeline the focused field should get.
 *
 * The distinction between [DEFAULT_ALNUM] and [SUPPRESSED] is deliberate: an email/URI/number
 * field is merely *usually* latin, so it only picks the starting input mode and the user can
 * still switch to romaji (a Japanese display name in a URL field is legitimate). A password
 * field is different in kind — its contents would be echoed into the candidate bar as
 * plaintext, so conversion stays off for as long as that field is focused.
 */
enum class ConversionPolicy(val initialInputMode: InputMode) {
    /** Ordinary text field: romaji composition and the candidate bar, as designed. */
    NORMAL(InputMode.ROMAJI),

    /** Latin-by-nature field: starts in direct alnum, but the user may switch back. */
    DEFAULT_ALNUM(InputMode.DIRECT_ALNUM),

    /** Password field: conversion off for the whole session, candidate bar hidden. */
    SUPPRESSED(InputMode.DIRECT_ALNUM),
}

/**
 * Picks a [ConversionPolicy] from [EditorInfo.inputType], the counterpart to
 * [EditorInfoActionMapper] for the Enter key. Without this every field — password prompts
 * included — opened in romaji mode with a live candidate bar.
 */
object EditorInfoInputModeMapper {
    fun mapConversionPolicy(editorInfo: EditorInfo?): ConversionPolicy {
        val inputType = editorInfo?.inputType ?: return ConversionPolicy.NORMAL
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                when (variation) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    -> ConversionPolicy.SUPPRESSED

                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_URI,
                    -> ConversionPolicy.DEFAULT_ALNUM

                    else -> ConversionPolicy.NORMAL
                }

            InputType.TYPE_CLASS_NUMBER ->
                if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                    ConversionPolicy.SUPPRESSED
                } else {
                    ConversionPolicy.DEFAULT_ALNUM
                }

            InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> ConversionPolicy.DEFAULT_ALNUM

            // TYPE_CLASS_NULL and anything unrecognised: treat as ordinary text.
            else -> ConversionPolicy.NORMAL
        }
    }
}
