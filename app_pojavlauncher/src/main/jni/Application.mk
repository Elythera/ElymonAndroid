# NDK_TOOLCHAIN_VERSION := 4.9
APP_PLATFORM := android-21
APP_STL := c++_shared
# ELYMON: Elymon ships arm64-v8a only (must match ndk.abiFilters in build.gradle)
APP_ABI := arm64-v8a
# ELYMON: align the libraries built here on 16 KB (NDK r27+) so they load on 16 KB page devices
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
