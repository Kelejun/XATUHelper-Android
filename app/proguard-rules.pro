-keepattributes Signature
-keepclassmembers class * { @com.google.gson.annotations.SerializedName <fields>; }
-keep class com.xatune.app.model.** { *; }
# Preserve the encrypted session JSON contract across debug/release updates.
-keep class com.xatune.app.data.Credentials { *; }
-keep class com.xatune.app.data.CookieRecord { *; }
-keep class com.xatune.app.data.Secrets { *; }
# Keep the local update-cache JSON stable across optimized app updates.
-keep class com.xatune.app.data.AppRelease { *; }
-keep class com.xatune.app.data.AppUpdateState { *; }
-keep class com.xatune.app.data.UpdateSettings { *; }
-keep class com.xatune.app.data.UpdateLog { *; }
-keep enum com.xatune.app.data.UpdateChannel { *; }
-keep enum com.xatune.app.data.UpdateServer { *; }

# Preserve persisted announcement history across optimized updates.
-keep class com.xatune.app.data.Announcement { *; }
-keep class com.xatune.app.data.ReceivedAnnouncement { *; }
