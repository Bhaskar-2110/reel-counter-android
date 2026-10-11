# R8 rules for release builds.
#
# Nothing is loaded by reflection: activities, services, widgets and the custom views used in
# layouts are kept by the rules the Android build generates from the manifest and layouts.

# Keep line numbers in crash reports (Settings → More → Permissions → Diagnostics), but not
# the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
