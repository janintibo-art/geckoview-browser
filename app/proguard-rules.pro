# GeckoView : le moteur s'appelle lui-meme par reflexion, ne rien renommer.
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.**

# Classes citees depuis le manifeste ou les gabarits XML.
-keep class com.example.geckobrowser.MainActivity { *; }
-keep class com.example.geckobrowser.SearchWidget { *; }
-keep class com.example.geckobrowser.StatsWidget { *; }

# Delegues instancies par GeckoView.
-keep class com.example.geckobrowser.Prompts { *; }
-keep class com.example.geckobrowser.Permissions { *; }

# Les messages echanges avec l'extension passent par org.json.
-keep class org.json.** { *; }

# Traces lisibles en cas de plantage.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
