# R8 rules for the release build. Libraries (Compose, MapLibre, coroutines, AndroidX) ship their own consumer rules;
# the app itself uses no reflection beyond Enum.valueOf (kept by proguard-android-optimize.txt). Resources looked up
# by name are kept in res/raw/keep.xml.

# Readable stack traces in bug reports: keep file names and line numbers (class names are still obfuscated;
# the mapping file is in app/build/outputs/mapping/release/).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
