LOCAL_PATH := $(call my-dir)
HERE_PATH := $(LOCAL_PATH)

# include $(HERE_PATH)/crash_dump/libbase/Android.mk
# include $(HERE_PATH)/crash_dump/libbacktrace/Android.mk
# include $(HERE_PATH)/crash_dump/debuggerd/Android.mk


LOCAL_PATH := $(HERE_PATH)

$(call import-module,prefab/bytehook)
LOCAL_PATH := $(HERE_PATH)


include $(CLEAR_VARS)
LOCAL_LDLIBS := -ldl -llog -landroid
LOCAL_MODULE := pojavexec
LOCAL_SHARED_LIBRARIES := driver_helper
LOCAL_CFLAGS += -rdynamic
LOCAL_SRC_FILES := \
    bigcoreaffinity.c \
    egl_bridge.c \
    ctxbridges/br_loader.c \
    ctxbridges/gl_bridge.c \
    ctxbridges/osm_bridge.c \
    ctxbridges/egl_loader.c \
    ctxbridges/osmesa_loader.c \
    ctxbridges/swap_interval_no_egl.c \
    ctxbridges/virgl_bridge.c \
    environ/environ.c \
    input_bridge_v3.c \
    jre_launcher.c \
    utils.c \
    stdio_is.c \
    java_exec_hooks.c \
    lwjgl_dlopen_hook.c

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
LOCAL_LDLIBS += -lEGL -lGLESv2
endif
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := exithook
LOCAL_LDLIBS := -ldl -llog
LOCAL_SHARED_LIBRARIES := bytehook pojavexec
LOCAL_SRC_FILES := exit_hook.c
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_LDLIBS := -ldl -llog -landroid
LOCAL_MODULE := driver_helper
LOCAL_SRC_FILES := \
    driver_helper/driver_helper.c \
    driver_helper/nsbypass.c
LOCAL_CFLAGS += -g -rdynamic

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
LOCAL_LDLIBS += -lEGL -lGLESv2
endif
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := linkerhook
LOCAL_SRC_FILES := \
    linkerhook/linkerhook.cpp \
    linkerhook/linkerns.c
LOCAL_LDFLAGS := -z global
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := pojavexec_awt
LOCAL_SRC_FILES := \
    awt_bridge.c
include $(BUILD_SHARED_LIBRARY)


include $(CLEAR_VARS)
LOCAL_MODULE := awt_headless
include $(BUILD_SHARED_LIBRARY)


LOCAL_PATH := $(HERE_PATH)/awt_xawt
include $(CLEAR_VARS)
LOCAL_MODULE := awt_xawt
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)
LOCAL_SHARED_LIBRARIES := awt_headless
LOCAL_SRC_FILES := xawt_fake.c
include $(BUILD_SHARED_LIBRARY)

# Disable Android heap pointer tagging for child OpenJDK installer processes.
# Used by Forge/NeoForge installer ProcessBuilder path through LD_PRELOAD.
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := disable_heap_tagging
LOCAL_SRC_FILES := disable_heap_tagging.c
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)


# PulseAudio shim for Minecraft Snapshot 7+ / dev.onvoid.webrtc desktop Linux native.
# Android does not ship libpulse.so.0, but WebRTC's linux-aarch64 native asks
# for it. Build libpulse.so with SONAME libpulse.so.0, then preload it from
# Java before launching Minecraft.
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := pulse
LOCAL_SRC_FILES := pulse_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libpulse.so.0
include $(BUILD_SHARED_LIBRARY)


# udev shim for Minecraft Snapshot 7+ / dev.onvoid.webrtc desktop Linux native.
# Android does not ship libudev.so.1, but WebRTC's linux-aarch64 native asks
# for it after the PulseAudio shim is satisfied. Build libudev.so with SONAME
# libudev.so.1, then preload it from Java before launching Minecraft.
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := udev
LOCAL_SRC_FILES := udev_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libudev.so.1
include $(BUILD_SHARED_LIBRARY)



# X11 shim for Minecraft Snapshot 7+ / dev.onvoid.webrtc desktop Linux native.
# Android does not ship libX11.so.6, but WebRTC's linux-aarch64 native asks
# for it after PulseAudio and udev are satisfied. Build libX11.so with SONAME
# libX11.so.6, then preload it from Java before launching Minecraft.
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := X11
LOCAL_SRC_FILES := x11_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libX11.so.6
include $(BUILD_SHARED_LIBRARY)


# Xfixes shim for Minecraft Snapshot 7+ / dev.onvoid.webrtc desktop Linux native.
# Android does not ship libXfixes.so.3, but WebRTC's linux-aarch64 native asks
# for it after PulseAudio, udev, and X11 are satisfied. Build libXfixes.so
# with SONAME libXfixes.so.3, then preload it from Java before launching Minecraft.
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xfixes
LOCAL_SRC_FILES := xfixes_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXfixes.so.3
include $(BUILD_SHARED_LIBRARY)


# Xrandr shim for Minecraft Snapshot 7+ / dev.onvoid.webrtc desktop Linux native.
# Android does not ship libXrandr.so.2, but WebRTC's linux-aarch64 native asks
# for it after PulseAudio, udev, X11, and Xfixes are satisfied.
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xrandr
LOCAL_SRC_FILES := xrandr_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXrandr.so.2
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXcomposite.so.1
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xcomposite
LOCAL_SRC_FILES := xcomposite_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXcomposite.so.1
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXdamage.so.1
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xdamage
LOCAL_SRC_FILES := xdamage_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXdamage.so.1
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXrender.so.1
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xrender
LOCAL_SRC_FILES := xrender_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXrender.so.1
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXext.so.6
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xext
LOCAL_SRC_FILES := xext_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXext.so.6
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXi.so.6
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xi
LOCAL_SRC_FILES := xi_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXi.so.6
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXtst.so.6
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xtst
LOCAL_SRC_FILES := xtst_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXtst.so.6
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXcursor.so.1
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xcursor
LOCAL_SRC_FILES := xcursor_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXcursor.so.1
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXinerama.so.1
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xinerama
LOCAL_SRC_FILES := xinerama_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXinerama.so.1
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXss.so.1
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xss
LOCAL_SRC_FILES := xss_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXss.so.1
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libxcb.so.1
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := xcb
LOCAL_SRC_FILES := xcb_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libxcb.so.1
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXau.so.6
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xau
LOCAL_SRC_FILES := xau_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXau.so.6
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libXdmcp.so.6
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := Xdmcp
LOCAL_SRC_FILES := xdmcp_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libXdmcp.so.6
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libdrm.so.2
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := drm
LOCAL_SRC_FILES := drm_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libdrm.so.2
include $(BUILD_SHARED_LIBRARY)


# Desktop Linux compatibility shim for Minecraft Snapshot WebRTC: libgbm.so.1
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := gbm
LOCAL_SRC_FILES := gbm_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libgbm.so.1
include $(BUILD_SHARED_LIBRARY)


# DBus shim for Minecraft Snapshot 7+ / dev.onvoid.webrtc desktop Linux native.
# Android does not ship libdbus-1.so.3, but WebRTC's linux-aarch64 native asks
# for it after the X11-side shim dependencies are satisfied.
LOCAL_PATH := $(HERE_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := dbus-1
LOCAL_SRC_FILES := dbus_stub.c
LOCAL_LDFLAGS += -Wl,-soname,libdbus-1.so.3
include $(BUILD_SHARED_LIBRARY)


# awt_headless cleanup removed: the old rm shell call breaks Windows ndk-build hosts.
