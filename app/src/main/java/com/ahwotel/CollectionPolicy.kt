package com.ahwotel

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

enum class CollectionPauseReason { SCREEN_OFF, LOCKED }

fun collectionPauseReason(settings: Settings, screenOn: Boolean, locked: Boolean): CollectionPauseReason? = when {
    settings.pauseWhenLocked && locked -> CollectionPauseReason.LOCKED
    !settings.collectScreenOff && !screenOn -> CollectionPauseReason.SCREEN_OFF
    else -> null
}

fun collectionPauseReason(context: Context, settings: Settings): CollectionPauseReason? = collectionPauseReason(
    settings, context.getSystemService(PowerManager::class.java).isInteractive,
    context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
)

fun collectionPaused(context: Context, settings: Settings) = collectionPauseReason(context, settings) != null

internal inline fun <T> readIfAllowed(paused: () -> Boolean, read: () -> T): T? =
    if (paused()) null else read()
