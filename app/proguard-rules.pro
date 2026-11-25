# Kover ProGuard rules.
#
# Minification is currently disabled (isMinifyEnabled = false), but this file is
# referenced by the release build type. Keep it in the repo so enabling R8 later
# does not break the build.
#
# Hilt/Dagger, Compose, Room and kotlinx.serialization ship their own consumer
# rules. Add project-specific keep rules here when minification is turned on.

# Keep serialized DTOs used by kotlinx.serialization if R8 starts stripping them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
