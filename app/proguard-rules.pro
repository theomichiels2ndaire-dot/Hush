# Hush — règles R8
# Les sessions média et les actions Tidal passent par des noms de classes système : on garde le support compat.
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }
# Widget (Glance) : actions appelées par réflexion
-keep class * extends androidx.glance.appwidget.action.ActionCallback { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-dontwarn org.jetbrains.annotations.**
